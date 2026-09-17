package com.xnlp.server.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.core.api.NlpContext;
import com.xnlp.server.dto.PageResponse;
import com.xnlp.server.dto.pipeline.*;
import com.xnlp.server.nlp.CapabilityRegistry;
import com.xnlp.server.pipeline.dag.*;
import com.xnlp.server.pipeline.persistence.*;
import com.xnlp.server.security.TenantAuthorizationService;
import com.xnlp.server.security.TenantRole;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class PipelineDagService {
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_ATTEMPTS = 1;
    private static final int EVENT_LIMIT = 10_000;
    private static final List<String> TEXT_KEYS = List.of("outputText", "normalizedText", "summary", "translation", "output", "corrected");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Map<String, String> CAPABILITY_ALIASES = Map.of(
            "TEXT_NORMALIZATION", "COR",
            "TEXT_SUMMARIZATION", "ABSUM");

    private final PipelineRepository repository;
    private final CapabilityRegistry capabilities;
    private final TenantAuthorizationService authorization;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final PipelineDagPlanner planner = new PipelineDagPlanner();
    private final PipelineFanInInputMerger inputMerger = new PipelineFanInInputMerger();

    public PipelineDagService(PipelineRepository repository, CapabilityRegistry capabilities,
                              TenantAuthorizationService authorization, ObjectMapper mapper,
                              @Qualifier("pipelineTaskExecutor") ExecutorService executor) {
        this.repository = repository;
        this.capabilities = capabilities;
        this.authorization = authorization;
        this.mapper = mapper;
        this.executor = executor;
    }

    public PipelineResponse create(PipelineCreateRequest request) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER);
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        PipelineDefinition definition = toDefinition(tenantId, id, request.name(), request.description(),
                request.nodes(), request.edges(), request.defaults(), actor(), now, now);
        validate(definition);
        return response(repository.createDefinition(definition));
    }

    public PipelineResponse update(String id, PipelineUpdateRequest request) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER);
        PipelineDefinition current = repository.findDefinition(tenantId, id)
                .orElseThrow(PipelineDagException::pipelineNotFound);
        long expectedInternalVersion = request.version() - 1;
        PipelineDefinition requested = toDefinition(tenantId, id, request.name(), request.description(),
                request.nodes(), request.edges(), request.defaults(), current.createdBy(),
                current.createdAt(), Instant.now());
        validate(requested);
        PipelineWriteResult result = repository.updateDefinition(requested, expectedInternalVersion);
        if (result.status() == PipelineWriteStatus.CONFLICT) {
            throw PipelineDagException.versionConflict(result.definition().version() + 1);
        }
        return response(result.definition());
    }

    public PipelineRunResponse start(String pipelineId, PipelineRunCreateRequest request) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER);
        PipelineDefinition definition = repository.findDefinition(tenantId, pipelineId)
                .orElseThrow(PipelineDagException::pipelineNotFound);
        validateOverrides(definition, request.overrides());
        Instant now = Instant.now();
        String runId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("input", request.input());
        envelope.put("overrides", request.overrides());
        if (request.idempotencyKey() != null) envelope.put("idempotencyKey", request.idempotencyKey());
        PipelineRun run = new PipelineRun(tenantId, runId, pipelineId, definition.version(),
                PipelineRunStatus.QUEUED, json(envelope), null, null, null, false, 0,
                actor(), now, null, now, null);
        repository.createRun(run);
        event(tenantId, runId, "run.queued", null, null, Map.of("status", "queued"));
        executor.execute(() -> TenantContext.runWithTenant(tenantId, () -> {
            sleep(75);
            execute(tenantId, runId);
        }));
        return runResponse(run, definition, List.of());
    }

    public PipelineResponse get(String pipelineId) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER);
        return response(repository.findDefinition(tenantId, pipelineId)
                .orElseThrow(PipelineDagException::pipelineNotFound));
    }

    public PageResponse<PipelineRunResponse> listRuns(
            String status, String pipelineId, int page, int size) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER);
        PipelineRunStatus statusFilter = parseStatus(status);
        PipelineRunPage result = repository.findRuns(tenantId, statusFilter, pipelineId, page, size);
        Map<String, PipelineDefinition> definitions = new HashMap<>();
        List<PipelineRunResponse> items = result.items().stream().map(run -> {
            String key = run.pipelineId() + '\u0000' + run.pipelineVersion();
            PipelineDefinition definition = definitions.computeIfAbsent(key, ignored ->
                    repository.findDefinitionVersion(tenantId, run.pipelineId(), run.pipelineVersion())
                            .orElseThrow(PipelineDagException::pipelineNotFound));
            return runResponse(run, definition, repository.findNodeAttempts(tenantId, run.runId()));
        }).toList();
        return PageResponse.of(items, page, size, result.total());
    }

    public PipelineRunResponse getRun(String runId) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER);
        PipelineRun run = repository.findRun(tenantId, runId).orElseThrow(PipelineDagException::runNotFound);
        PipelineDefinition definition = repository.findDefinitionVersion(tenantId, run.pipelineId(), run.pipelineVersion())
                .orElseThrow(PipelineDagException::pipelineNotFound);
        return runResponse(run, definition, repository.findNodeAttempts(tenantId, runId));
    }

    public PipelineRunResponse cancel(String runId) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER);
        PipelineRun run = repository.findRun(tenantId, runId).orElseThrow(PipelineDagException::runNotFound);
        if (run.status().terminal()) throw PipelineDagException.runTerminal();
        if (!run.cancelRequested() && repository.requestCancellation(tenantId, runId, run.revision(), Instant.now())) {
            event(tenantId, runId, "run.cancelling", null, null, Map.of("status", "cancelling"));
        }
        PipelineRun current = repository.findRun(tenantId, runId).orElseThrow();
        PipelineDefinition definition = repository.findDefinitionVersion(tenantId, current.pipelineId(), current.pipelineVersion()).orElseThrow();
        return runResponse(current, definition, repository.findNodeAttempts(tenantId, runId));
    }

    public List<PipelineEventResponse> events(String runId, long afterSequence) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER);
        repository.findRun(tenantId, runId).orElseThrow(PipelineDagException::runNotFound);
        return repository.findEvents(tenantId, runId, afterSequence, EVENT_LIMIT).stream().map(this::eventResponse).toList();
    }

    public SseEmitter streamEvents(String runId, long afterSequence) {
        List<PipelineEventResponse> events = events(runId, afterSequence);
        SseEmitter emitter = new SseEmitter(30_000L);
        executor.execute(() -> {
            try {
                for (PipelineEventResponse event : events) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.type()).data(event));
                }
                emitter.complete();
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    public PipelineTraceDownloadResponse trace(String runId) {
        String tenantId = tenant();
        authorization.require(tenantId, TenantRole.ADMIN, TenantRole.DEVELOPER, TenantRole.VIEWER);
        PipelineRun run = repository.findRun(tenantId, runId).orElseThrow(PipelineDagException::runNotFound);
        Instant deadline = Instant.now().plusSeconds(5);
        while (!run.status().terminal() && Instant.now().isBefore(deadline)) {
            sleep(10);
            run = repository.findRun(tenantId, runId).orElseThrow(PipelineDagException::runNotFound);
        }
        if (!run.status().terminal()) throw PipelineDagException.traceNotReady();
        PipelineDefinition definition = repository.findDefinitionVersion(tenantId, run.pipelineId(), run.pipelineVersion()).orElseThrow();
        List<PipelineNodeAttempt> attempts = repository.findNodeAttempts(tenantId, runId);
        List<PipelineNodeTraceResponse> nodes = definition.nodes().stream().map(node -> nodeTrace(node, attempts)).toList();
        return new PipelineTraceDownloadResponse(run.runId(), run.runId(), run.pipelineId(), run.pipelineVersion() + 1,
                status(run.status()), run.startedAt(), run.completedAt(), duration(run.startedAt(), run.completedAt()), nodes,
                repository.findEvents(tenantId, runId, 0, EVENT_LIMIT).stream().map(this::eventResponse).toList());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (PipelineRun run : repository.findRecoverableRuns()) {
            executor.execute(() -> TenantContext.runWithTenant(run.tenantId(), () -> execute(run.tenantId(), run.runId())));
        }
    }

    private void execute(String tenantId, String runId) {
        PipelineRun run = repository.findRun(tenantId, runId).orElse(null);
        if (run == null) return;
        if (run.status() == PipelineRunStatus.CANCELLING) { finishCancelled(run); return; }
        if (run.status() == PipelineRunStatus.QUEUED) {
            if (!repository.compareAndSetRunStatus(tenantId, runId, PipelineRunStatus.QUEUED,
                    PipelineRunStatus.RUNNING, run.revision(), Instant.now(), null, null, null)) return;
            event(tenantId, runId, "run.started", null, null, Map.of("status", "running"));
            run = repository.findRun(tenantId, runId).orElseThrow();
        }
        if (run.status() != PipelineRunStatus.RUNNING) return;
        PipelineDefinition definition = repository.findDefinitionVersion(tenantId, run.pipelineId(), run.pipelineVersion()).orElseThrow();
        PipelineDagPlan plan = planner.plan(toDag(definition));
        Map<String, Object> envelope = map(run.inputJson());
        Map<String, Object> originalInput = objectMap(envelope.get("input"));
        Map<String, PipelineNodeOverrideRequest> overrides = overrides(envelope.get("overrides"));
        Map<String, Map<String, Object>> outputs = new ConcurrentHashMap<>();
        try {
            for (List<com.xnlp.server.pipeline.dag.PipelineDagNode> batch : plan.batches()) {
                PipelineRun current = repository.findRun(tenantId, runId).orElseThrow();
                if (current.cancelRequested()) { finishCancelled(current); return; }
                List<CompletableFuture<Void>> futures = batch.stream().map(node -> CompletableFuture.runAsync(
                        () -> executeNode(tenantId, runId, definition, node.nodeId(), originalInput,
                                plan.predecessorNodeIds().get(node.nodeId()), outputs, overrides.get(node.nodeId())), executor)).toList();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            }
            Map<String, Object> finalOutput = inputMerger.merge(originalInput, outputs);
            PipelineRun latest = repository.findRun(tenantId, runId).orElseThrow();
            if (latest.cancelRequested()) { finishCancelled(latest); return; }
            if (repository.compareAndSetRunStatus(tenantId, runId, PipelineRunStatus.RUNNING,
                    PipelineRunStatus.COMPLETED, latest.revision(), Instant.now(), json(finalOutput), null, null)) {
                event(tenantId, runId, "run.completed", null, null, Map.of("status", "completed"));
            }
        } catch (RuntimeException ex) {
            PipelineRun latest = repository.findRun(tenantId, runId).orElse(null);
            if (latest != null && latest.cancelRequested()) { finishCancelled(latest); return; }
            if (latest != null && latest.status() == PipelineRunStatus.RUNNING
                    && repository.compareAndSetRunStatus(tenantId, runId, PipelineRunStatus.RUNNING,
                    PipelineRunStatus.FAILED, latest.revision(), Instant.now(), null,
                    "pipeline_execution_failed", safeMessage(ex))) {
                event(tenantId, runId, "run.failed", null, null,
                        Map.of("status", "failed", "errorCode", "pipeline_execution_failed"));
            }
        }
    }

    private void executeNode(String tenantId, String runId, PipelineDefinition definition, String nodeId,
                             Map<String, Object> originalInput, List<String> predecessorIds,
                             Map<String, Map<String, Object>> outputs, PipelineNodeOverrideRequest override) {
        PipelineNodeDefinition node = definition.nodes().stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElseThrow();
        Map<String, Map<String, Object>> predecessorOutputs = predecessorIds.stream()
                .filter(outputs::containsKey).collect(Collectors.toMap(id -> id, outputs::get));
        Map<String, Object> input = inputMerger.merge(originalInput, predecessorOutputs);
        NodeConfig config = nodeConfig(node.configJson());
        Map<String, Object> parameters = new LinkedHashMap<>(config.parameters());
        long timeoutMillis = node.timeoutMillis();
        int maxAttempts = node.maxAttempts();
        long backoffMillis = config.backoffMillis();
        if (override != null) {
            if (override.parameters() != null) parameters.putAll(override.parameters());
            if (override.timeoutSeconds() != null) timeoutMillis = override.timeoutSeconds() * 1_000L;
            if (override.retry() != null) {
                maxAttempts = override.retry().maxAttempts();
                backoffMillis = override.retry().backoffMillis();
            }
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            PipelineRun current = repository.findRun(tenantId, runId).orElseThrow();
            if (current.cancelRequested()) throw new CancellationException("Pipeline run was cancelled");
            Instant started = Instant.now();
            repository.createNodeAttempt(new PipelineNodeAttempt(tenantId, runId, nodeId, attempt,
                    NodeAttemptStatus.RUNNING, json(input), null, null, null, started, null));
            event(tenantId, runId, "node.started", nodeId, attempt, Map.of("status", "running"));
            final Map<String, Object> executionParameters = Map.copyOf(parameters);
            Future<Map<String, Object>> future = executor.submit(() -> TenantContext.callWithTenant(tenantId,
                    () -> invoke(node.capabilityId(), input, executionParameters)));
            try {
                Map<String, Object> output = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                boolean completed = repository.completeNodeAttempt(tenantId, runId, nodeId, attempt,
                        NodeAttemptStatus.RUNNING, NodeAttemptStatus.SUCCEEDED, Instant.now(), json(output), null, null);
                if (!completed) throw new CancellationException("Pipeline run no longer accepts node output");
                outputs.put(nodeId, output);
                event(tenantId, runId, "node.completed", nodeId, attempt, Map.of("status", "completed"));
                return;
            } catch (TimeoutException ex) {
                future.cancel(true);
                repository.completeNodeAttempt(tenantId, runId, nodeId, attempt, NodeAttemptStatus.RUNNING,
                        NodeAttemptStatus.TIMED_OUT, Instant.now(), null, "node_timeout", "Node execution timed out");
                event(tenantId, runId, "node.timed_out", nodeId, attempt, Map.of("status", "timed_out"));
                if (attempt == maxAttempts) throw new CompletionException(ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CompletionException(ex);
            } catch (ExecutionException ex) {
                String message = safeMessage(ex.getCause());
                repository.completeNodeAttempt(tenantId, runId, nodeId, attempt, NodeAttemptStatus.RUNNING,
                        NodeAttemptStatus.FAILED, Instant.now(), null, "node_execution_failed", message);
                event(tenantId, runId, "node.failed", nodeId, attempt,
                        Map.of("status", "failed", "errorCode", "node_execution_failed"));
                if (attempt == maxAttempts) throw new CompletionException(ex.getCause());
            }
            sleep(backoffMillis);
        }
    }

    private Map<String, Object> invoke(String capability, Map<String, Object> input, Map<String, Object> parameters) {
        String text = Objects.toString(input.getOrDefault("text", findText(input)), "");
        String pair = Objects.toString(input.getOrDefault("textPair", ""), "");
        String language = Objects.toString(input.getOrDefault("language", "zh"), "zh");
        ComponentResult result = capabilities.execute(resolveCapability(capability), NlpContext.builder().text(text)
                .textPair(pair).language(language).params(parameters).build());
        return result.getData();
    }

    private Object findText(Map<String, Object> input) {
        for (String key : TEXT_KEYS) if (input.get(key) instanceof String value) return value;
        return "";
    }

    private PipelineDefinition toDefinition(String tenantId, String id, String name, String description,
                                            List<PipelineNodeRequest> nodes, List<PipelineEdgeRequest> edges,
                                            PipelineExecutionDefaultsRequest defaults, String createdBy,
                                            Instant createdAt, Instant updatedAt) {
        int defaultTimeout = defaults == null || defaults.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : defaults.timeoutSeconds();
        int defaultAttempts = defaults == null || defaults.retry() == null ? DEFAULT_MAX_ATTEMPTS : defaults.retry().maxAttempts();
        long defaultBackoff = defaults == null || defaults.retry() == null ? 0 : defaults.retry().backoffMillis();
        List<PipelineNodeDefinition> definitions = nodes.stream().map(node -> {
            int timeout = node.timeoutSeconds() == null ? defaultTimeout : node.timeoutSeconds();
            int attempts = node.retry() == null ? defaultAttempts : node.retry().maxAttempts();
            long backoff = node.retry() == null ? defaultBackoff : node.retry().backoffMillis();
            return new PipelineNodeDefinition(node.id(), node.capability(),
                    json(new NodeConfig(node.name(), node.parameters(), backoff)), timeout * 1_000L, attempts);
        }).toList();
        List<PipelineEdgeDefinition> edgeDefinitions = (edges == null ? List.<PipelineEdgeRequest>of() : edges).stream()
                .map(edge -> new PipelineEdgeDefinition(edge.sourceNodeId(), edge.targetNodeId(), edge.sourceOutput(), edge.targetInput())).toList();
        return new PipelineDefinition(tenantId, id, name, description, 0, definitions, edgeDefinitions,
                createdBy, createdAt, updatedAt);
    }

    private void validate(PipelineDefinition definition) {
        for (PipelineNodeDefinition node : definition.nodes()) {
            if (capabilities.get(resolveCapability(node.capabilityId())).isEmpty()) {
                throw com.xnlp.server.pipeline.dag.PipelineDagException.invalid("Unsupported capability: " + node.capabilityId());
            }
        }
        planner.plan(toDag(definition));
    }

    private PipelineDag toDag(PipelineDefinition definition) {
        return new PipelineDag(definition.nodes().stream()
                .map(node -> new com.xnlp.server.pipeline.dag.PipelineDagNode(node.nodeId(), node.capabilityId())).toList(),
                definition.edges().stream().map(edge -> new PipelineDagEdge(edge.sourceNodeId(), edge.targetNodeId())).toList());
    }

    private void validateOverrides(PipelineDefinition definition, Map<String, PipelineNodeOverrideRequest> overrides) {
        Set<String> ids = definition.nodes().stream().map(PipelineNodeDefinition::nodeId).collect(Collectors.toSet());
        if (!ids.containsAll(overrides.keySet())) throw com.xnlp.server.pipeline.dag.PipelineDagException.invalid("Override references an unknown node");
    }

    private PipelineResponse response(PipelineDefinition definition) {
        List<PipelineNodeResponse> nodes = definition.nodes().stream().map(node -> {
            NodeConfig config = nodeConfig(node.configJson());
            return new PipelineNodeResponse(node.nodeId(), node.capabilityId(), config.name(), config.parameters(),
                    Math.toIntExact(node.timeoutMillis() / 1_000L), new PipelineRetryPolicyResponse(node.maxAttempts(), config.backoffMillis()));
        }).toList();
        List<PipelineEdgeResponse> edges = definition.edges().stream().map(edge -> new PipelineEdgeResponse(
                edge.sourceNodeId(), edge.targetNodeId(), edge.sourceOutput(), edge.targetInput())).toList();
        int maxTimeout = nodes.stream().map(PipelineNodeResponse::timeoutSeconds).max(Integer::compareTo).orElse(DEFAULT_TIMEOUT_SECONDS);
        int maxAttempts = nodes.stream().map(n -> n.retry().maxAttempts()).max(Integer::compareTo).orElse(DEFAULT_MAX_ATTEMPTS);
        long maxBackoff = nodes.stream().map(n -> n.retry().backoffMillis()).max(Long::compareTo).orElse(0L);
        return new PipelineResponse(definition.id(), definition.version() + 1, definition.name(), definition.description(),
                "active", nodes, edges, new PipelineExecutionDefaultsResponse(maxTimeout,
                new PipelineRetryPolicyResponse(maxAttempts, maxBackoff), Math.max(1, nodes.size())),
                definition.createdBy(), definition.createdAt(), definition.updatedAt());
    }

    private PipelineRunResponse runResponse(PipelineRun run, PipelineDefinition definition, List<PipelineNodeAttempt> attempts) {
        List<PipelineNodeRunResponse> nodes = definition.nodes().stream().map(node -> nodeRun(node, attempts)).toList();
        Map<String, Object> envelope = map(run.inputJson());
        return new PipelineRunResponse(run.runId(), run.pipelineId(), run.pipelineVersion() + 1, status(run.status()),
                objectMap(envelope.get("input")), map(run.outputJson()), run.cancelRequested(), nodes,
                run.errorCode(), run.errorMessage(), run.createdAt(), run.startedAt(), run.completedAt());
    }

    private PipelineNodeRunResponse nodeRun(PipelineNodeDefinition node, List<PipelineNodeAttempt> all) {
        PipelineNodeAttempt latest = all.stream().filter(a -> a.nodeId().equals(node.nodeId()))
                .max(Comparator.comparingInt(PipelineNodeAttempt::attempt)).orElse(null);
        if (latest == null) return new PipelineNodeRunResponse(node.nodeId(), node.capabilityId(), "pending", 0,
                node.maxAttempts(), Map.of(), Map.of(), null, null, null, null, null);
        return new PipelineNodeRunResponse(node.nodeId(), node.capabilityId(), status(latest.status()), latest.attempt(),
                node.maxAttempts(), map(latest.inputJson()), map(latest.outputJson()), latest.errorCode(), latest.errorMessage(),
                latest.startedAt(), latest.completedAt(), duration(latest.startedAt(), latest.completedAt()));
    }

    private PipelineNodeTraceResponse nodeTrace(PipelineNodeDefinition node, List<PipelineNodeAttempt> all) {
        List<PipelineNodeAttempt> attempts = all.stream().filter(a -> a.nodeId().equals(node.nodeId()))
                .sorted(Comparator.comparingInt(PipelineNodeAttempt::attempt)).toList();
        List<PipelineNodeAttemptResponse> responses = attempts.stream().map(a -> new PipelineNodeAttemptResponse(
                a.attempt(), status(a.status()), map(a.inputJson()), map(a.outputJson()), a.errorCode(), a.errorMessage(),
                a.startedAt(), a.completedAt(), duration(a.startedAt(), a.completedAt()))).toList();
        PipelineNodeAttempt latest = attempts.isEmpty() ? null : attempts.getLast();
        return new PipelineNodeTraceResponse(node.nodeId(), node.capabilityId(), latest == null ? "pending" : status(latest.status()),
                responses, latest == null ? Map.of() : map(latest.outputJson()), latest == null ? null : latest.errorCode(),
                latest == null ? null : latest.errorMessage());
    }

    private PipelineEventResponse eventResponse(PipelineRunEvent event) {
        Map<String, Object> detail = map(event.payloadJson());
        return new PipelineEventResponse(event.sequence(), event.runId(), event.eventType(),
                Objects.toString(detail.getOrDefault("status", ""), ""), event.nodeId(), event.attempt(), detail, event.occurredAt());
    }

    private void finishCancelled(PipelineRun run) {
        PipelineRun current = repository.findRun(run.tenantId(), run.runId()).orElse(run);
        PipelineRunStatus expected = current.status();
        if (expected == PipelineRunStatus.QUEUED || expected == PipelineRunStatus.RUNNING) {
            repository.requestCancellation(current.tenantId(), current.runId(), current.revision(), Instant.now());
            current = repository.findRun(current.tenantId(), current.runId()).orElseThrow();
            expected = current.status();
        }
        if (expected == PipelineRunStatus.CANCELLING && repository.compareAndSetRunStatus(current.tenantId(), current.runId(),
                expected, PipelineRunStatus.CANCELLED, current.revision(), Instant.now(), null, null, null)) {
            event(current.tenantId(), current.runId(), "run.cancelled", null, null, Map.of("status", "cancelled"));
        }
    }

    private void event(String tenantId, String runId, String type, String nodeId, Integer attempt, Map<String, Object> detail) {
        repository.appendEvent(new PipelineRunEvent(tenantId, runId, 0, type, nodeId, attempt, json(detail), Instant.now()));
    }


    private static PipelineRunStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return PipelineRunStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown pipeline run status: " + value);
        }
    }

    private static String resolveCapability(String capability) {
        return CAPABILITY_ALIASES.getOrDefault(capability.toUpperCase(Locale.ROOT), capability);
    }

    private String tenant() { return TenantContext.currentTenantId(); }
    private String actor() { return authorization.currentPrincipal().subject(); }
    private static String status(Enum<?> status) { return status.name().toLowerCase(Locale.ROOT); }
    private static Long duration(Instant start, Instant end) { return start == null || end == null ? null : Duration.between(start, end).toMillis(); }
    private static String safeMessage(Throwable ex) { return ex == null || ex.getMessage() == null || ex.getMessage().isBlank() ? "Pipeline execution failed" : ex.getMessage(); }
    private static void sleep(long millis) { if (millis <= 0) return; try { Thread.sleep(millis); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CompletionException(ex); } }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (JsonProcessingException ex) { throw new IllegalArgumentException("Value cannot be serialized", ex); }
    }
    private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return mapper.readValue(json, MAP_TYPE); } catch (JsonProcessingException ex) { throw new IllegalStateException("Persisted pipeline JSON is invalid", ex); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private NodeConfig nodeConfig(String json) {
        try { return mapper.readValue(json, NodeConfig.class); } catch (JsonProcessingException ex) { throw new IllegalStateException("Persisted node configuration is invalid", ex); }
    }
    private Map<String, PipelineNodeOverrideRequest> overrides(Object value) {
        if (value == null) return Map.of();
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    private record NodeConfig(String name, Map<String, Object> parameters, long backoffMillis) {
        private NodeConfig { parameters = parameters == null ? Map.of() : Map.copyOf(parameters); }
    }
}
