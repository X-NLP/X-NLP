package com.xnlp.server.service;

import com.xnlp.core.eval.*;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.repository.EvaluationRunRepository;
import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.tenant.TenantContext;
import com.xnlp.server.dataset.versioning.DatasetSnapshot;
import com.xnlp.server.dataset.versioning.VersionedDatasetEntry;
import com.xnlp.server.dto.EvaluationRetryResponse;
import com.xnlp.server.dto.EvaluationSampleResultResponse;
import com.xnlp.server.dto.PageResponse;
import com.xnlp.server.evaluation.recovery.EvaluationDatasetSnapshotResolver;
import com.xnlp.server.evaluation.recovery.EvaluationRecoveryCoordinator;
import com.xnlp.server.evaluation.recovery.EvaluationRecoveryException;
import com.xnlp.server.evaluation.recovery.EvaluationRecoveryRepository;
import com.xnlp.server.evaluation.recovery.EvaluationSampleCommit;
import com.xnlp.server.evaluation.recovery.EvaluationSampleResult;
import com.xnlp.server.evaluation.recovery.RecoveryLease;
import com.xnlp.server.evaluation.recovery.RecoveryRun;
import com.xnlp.server.evaluation.recovery.RecoveryRunStatus;
import com.xnlp.server.evaluation.recovery.SampleResultStatus;
import com.xnlp.server.security.TenantAuthorizationService;
import com.xnlp.server.security.XnlpPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;

/**
 * Orchestrates NLP model evaluation against datasets.
 *
 * <p>Evaluation is submitted to a bounded executor so HTTP requests return
 * quickly. Progress and terminal state are persisted after each entry, which
 * makes the same API useful for a web UI, CLI and operational monitoring.</p>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed", "cancelled");
    private static final long LEASE_SECONDS = 60;

    private final ModelRegistry registry;
    private final DatasetService datasetService;
    private final EvaluationRunRepository runRepository;
    private final MetricsCalculator calculator;
    private final TaskExecutor evaluationTaskExecutor;
    private final EvaluationProgressPublisher progressPublisher;
    private final EvaluationRecoveryRepository recoveryRepository;
    private final EvaluationDatasetSnapshotResolver snapshots;
    private final TenantAuthorizationService authorization;
    private final ObjectMapper objectMapper;

    public EvaluationService(ModelRegistry registry, DatasetService datasetService,
                             EvaluationRunRepository runRepository,
                             MetricsCalculator calculator,
                             TaskExecutor evaluationTaskExecutor,
                             EvaluationProgressPublisher progressPublisher,
                             EvaluationRecoveryRepository recoveryRepository,
                             EvaluationDatasetSnapshotResolver snapshots,
                             EvaluationRecoveryCoordinator recoveryCoordinator,
                             TenantAuthorizationService authorization,
                             ObjectMapper objectMapper) {
        this.registry = registry;
        this.datasetService = datasetService;
        this.runRepository = runRepository;
        this.calculator = calculator;
        this.evaluationTaskExecutor = evaluationTaskExecutor;
        this.progressPublisher = progressPublisher;
        this.recoveryRepository = recoveryRepository;
        this.snapshots = snapshots;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        recoveryCoordinator.register(this::dispatchRecovery);
    }

    public List<EvaluationRun> listRuns() {
        return listRuns(null, null, null);
    }

    public List<EvaluationRun> listRuns(String modelName, String datasetName, String status) {
        return runRepository.findAll().stream()
                .filter(run -> matches(run.getModelName(), modelName))
                .filter(run -> matches(run.getDatasetName(), datasetName))
                .filter(run -> matches(run.getStatus(), status))
                .toList();
    }

    public Optional<EvaluationRun> getRun(String id) {
        return runRepository.findById(id);
    }

    /** Opens a resumable event stream whose first event is the latest persisted snapshot. */
    public SseEmitter streamProgress(String id) {
        if (getRun(id).isEmpty()) {
            throw new NoSuchElementException("Evaluation run not found: " + id);
        }
        return progressPublisher.subscribe(id, () -> getRun(id));
    }

    /** Creates and queues a durable run, returning before model inference begins. */
    public EvaluationRun startEvaluation(String modelName, String datasetId, NLPTaskType taskType) {
        EvaluationRequest request = prepareRequest(modelName, datasetId, taskType);
        dispatch(request.recovery());
        return request.run();
    }

    /** Blocking compatibility entry point used by existing SDK/CLI integrations. */
    public EvaluationRun runEvaluation(String modelName, String datasetId, NLPTaskType taskType) {
        EvaluationRequest request = prepareRequest(modelName, datasetId, taskType);
        executeEvaluation(request.recovery());
        return getRun(request.run().getId()).orElse(request.run());
    }

    public PageResponse<EvaluationSampleResultResponse> sampleResults(
            String runId, String status, int page, int size) {
        String tenantId = TenantContext.currentTenantId();
        RecoveryRun recovery = recoveryRepository.findRun(tenantId, runId)
                .orElseThrow(EvaluationRecoveryException::notFound);
        SampleResultStatus filter = sampleStatus(status);
        List<EvaluationSampleResultResponse> all = recoveryRepository.findSampleResults(tenantId, runId).stream()
                .filter(result -> filter == null || result.status() == filter)
                .map(result -> EvaluationSampleResultResponse.from(
                        result, recovery.parentRunId(), recovery.attempt(), objectMapper))
                .toList();
        int offset = Math.multiplyExact(page, size);
        List<EvaluationSampleResultResponse> items = offset >= all.size()
                ? List.of() : all.subList(offset, Math.min(all.size(), offset + size));
        return PageResponse.of(items, page, size, all.size());
    }

    public EvaluationRetryResponse retry(String sourceRunId, boolean failedOnly) {
        String tenantId = TenantContext.currentTenantId();
        RecoveryRun source = recoveryRepository.findRun(tenantId, sourceRunId)
                .orElseThrow(EvaluationRecoveryException::notFound);
        if (!source.status().terminal()) throw EvaluationRecoveryException.notRetryable();
        int selected = failedOnly
                ? recoveryRepository.findFailedSampleIds(tenantId, sourceRunId).size()
                : source.totalSamples();
        if (selected == 0) throw EvaluationRecoveryException.notRetryable();
        EvaluationRun sourceRun = runRepository.findById(sourceRunId)
                .orElseThrow(EvaluationRecoveryException::notFound);
        String retryId = UUID.randomUUID().toString();
        XnlpPrincipal principal = currentPrincipal();
        Instant now = Instant.now();
        RecoveryRun retry = recoveryRepository.createRetry(
                tenantId, retryId, sourceRunId, failedOnly, principal.subject(), now);
        EvaluationRun run = newRun(retryId, sourceRun.getModelName(), sourceRun.getDatasetId(),
                sourceRun.getDatasetName(), sourceRun.getTaskType(), source.datasetVersion(), selected, now);
        run.setParentRunId(sourceRunId);
        run.setRootRunId(source.rootRunId());
        run.setAttempt(retry.attempt());
        run.setRetryFailedOnly(failedOnly);
        saveAndPublish(run);
        dispatch(retry);
        return new EvaluationRetryResponse(sourceRunId, source.rootRunId(), failedOnly, selected, run);
    }

    /** Requests cancellation; a fenced worker observes it before committing another result. */
    public EvaluationRun cancel(String id) {
        EvaluationRun run = getRun(id).orElseThrow(EvaluationRecoveryException::notFound);
        String tenantId = TenantContext.currentTenantId();
        if (!TERMINAL_STATUSES.contains(run.getStatus())) {
            recoveryRepository.requestCancellation(tenantId, id, Instant.now());
            run.setCancelRequested(true);
            run.setStatus("cancelling");
            saveAndPublish(run);
        }
        return run;
    }

    private EvaluationRequest prepareRequest(String modelName, String datasetId, NLPTaskType taskType) {
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
        if (datasetId == null || datasetId.isBlank()) throw new IllegalArgumentException("datasetId must not be blank");
        String tenantId = TenantContext.currentTenantId();
        XnlpPrincipal principal = currentPrincipal();
        Instant now = Instant.now();
        var pinned = snapshots.pin(tenantId, datasetId, principal.subject(), now);
        NLPTaskType effectiveTask = taskType != null ? taskType : pinned.dataset().getTaskType();
        if (effectiveTask == null) throw new IllegalArgumentException("Task type must be specified or present on dataset");
        String runId = UUID.randomUUID().toString();
        int total = pinned.snapshot().entries().size();
        RecoveryRun recovery = recoveryRepository.createRun(
                tenantId, runId, datasetId, pinned.snapshot().version(), total, principal.subject(), now);
        EvaluationRun run = newRun(runId, modelName, datasetId, pinned.dataset().getName(),
                effectiveTask, pinned.snapshot().version(), total, now);
        saveAndPublish(run);
        return new EvaluationRequest(run, recovery);
    }

    private EvaluationRun newRun(
            String runId, String modelName, String datasetId, String datasetName, NLPTaskType taskType,
            long datasetVersion, int totalEntries, Instant now) {
        EvaluationRun run = new EvaluationRun();
        run.setId(runId);
        run.setModelName(modelName);
        run.setDatasetId(datasetId);
        run.setDatasetName(datasetName);
        run.setTaskType(taskType);
        run.setDatasetVersion(datasetVersion);
        run.setRootRunId(runId);
        run.setStatus("queued");
        run.setCreatedAt(now);
        run.setTotalEntries(totalEntries);
        run.setProcessedEntries(0);
        run.setProgressPercent(totalEntries == 0 ? 100 : 0);
        run.setCancelRequested(false);
        return run;
    }

    private void dispatch(RecoveryRun recovery) {
        try {
            evaluationTaskExecutor.execute(() -> TenantContext.runWithTenant(
                    recovery.tenantId(), () -> executeEvaluation(recovery)));
        } catch (RuntimeException rejected) {
            throw EvaluationRecoveryException.queueUnavailable();
        }
    }

    private void dispatchRecovery(RecoveryRun recovery) {
        try {
            dispatch(recovery);
        } catch (EvaluationRecoveryException unavailable) {
            log.warn("Unable to requeue evaluation run {}", recovery.runId());
        }
    }

    private void executeEvaluation(RecoveryRun recovery) {
        String tenantId = recovery.tenantId();
        String runId = recovery.runId();
        String ownerId = "worker-" + UUID.randomUUID();
        Instant claimedAt = Instant.now();
        RecoveryLease lease = recoveryRepository.tryClaimLease(
                tenantId, runId, ownerId, claimedAt, claimedAt.plusSeconds(LEASE_SECONDS)).orElse(null);
        if (lease == null) return;
        long t0 = System.nanoTime();
        try {
            if (recovery.status() == RecoveryRunStatus.CANCELLING) {
                finishTerminal(recovery, ownerId, lease.fencingToken(), RecoveryRunStatus.CANCELLING,
                        RecoveryRunStatus.CANCELLED, null, t0);
                return;
            }
            if (!recoveryRepository.markRunning(tenantId, runId, ownerId, lease.fencingToken(), Instant.now())) return;
            EvaluationRun run = runRepository.findById(runId).orElseThrow(EvaluationRecoveryException::notFound);
            run.setStatus("running");
            saveAndPublish(run);
            DatasetSnapshot snapshot = snapshots.resolve(tenantId, recovery.datasetId(), recovery.datasetVersion());
            List<VersionedDatasetEntry> work = workItems(recovery, snapshot);
            Set<String> completed = recoveryRepository.findSampleResults(tenantId, runId).stream()
                    .map(EvaluationSampleResult::sampleId).collect(java.util.stream.Collectors.toSet());

            for (int index = 0; index < work.size(); index++) {
                VersionedDatasetEntry entry = work.get(index);
                if (completed.contains(entry.data().id())) continue;
                RecoveryRun latest = recoveryRepository.findRun(tenantId, runId).orElseThrow();
                if (latest.status() == RecoveryRunStatus.CANCELLING) {
                    run.setCancelRequested(true);
                    finishTerminal(latest, ownerId, lease.fencingToken(), RecoveryRunStatus.CANCELLING,
                            RecoveryRunStatus.CANCELLED, null, t0);
                    return;
                }
                EvaluationSampleResult result = evaluateSample(recovery, run, entry, index);
                EvaluationSampleCommit commit = recoveryRepository.commitSample(
                        result, index + 1, ownerId, lease.fencingToken(), Instant.now());
                completed.add(commit.result().sampleId());
                List<EvaluationSampleResult> results = recoveryRepository.findSampleResults(tenantId, runId);
                run.setProcessedEntries(results.size());
                run.setProgressPercent(recovery.totalSamples() == 0 ? 100
                        : results.size() * 100.0 / recovery.totalSamples());
                saveAndPublish(run);
                lease = recoveryRepository.renewLease(tenantId, runId, ownerId, lease.fencingToken(),
                        Instant.now(), Instant.now().plusSeconds(LEASE_SECONDS)).orElseThrow();
            }

            List<EvaluationSampleResult> results = recoveryRepository.findSampleResults(tenantId, runId);
            List<Map.Entry<String, String>> predictions = results.stream()
                    .filter(result -> result.status() == SampleResultStatus.SUCCEEDED)
                    .map(result -> Map.entry(Objects.toString(result.expectedOutput(), ""),
                            Objects.toString(result.actualOutput(), ""))).toList();
            run.setMetrics(calculator.compute(run.getTaskType(), predictions));
            run.setProcessedEntries(results.size());
            run.setProgressPercent(100);
            boolean anyFailed = results.stream().anyMatch(result -> result.status() == SampleResultStatus.FAILED);
            RecoveryRunStatus terminal = anyFailed ? RecoveryRunStatus.FAILED : RecoveryRunStatus.COMPLETED;
            finishTerminal(recoveryRepository.findRun(tenantId, runId).orElseThrow(), ownerId,
                    lease.fencingToken(), RecoveryRunStatus.RUNNING, terminal,
                    anyFailed ? "One or more evaluation samples failed" : null, t0);
        } catch (RuntimeException failure) {
            log.error("Evaluation failed: runId={}", runId, failure);
            RecoveryRun latest = recoveryRepository.findRun(tenantId, runId).orElse(null);
            if (latest != null && latest.status() == RecoveryRunStatus.RUNNING) {
                finishTerminal(latest, ownerId, lease.fencingToken(), RecoveryRunStatus.RUNNING,
                        RecoveryRunStatus.FAILED, safeMessage(failure), t0);
            }
        } finally {
            recoveryRepository.releaseLease(tenantId, runId, ownerId, lease.fencingToken());
        }
    }

    private EvaluationSampleResult evaluateSample(
            RecoveryRun recovery, EvaluationRun run, VersionedDatasetEntry versioned, int workIndex) {
        String expected = versioned.data().expectedOutput();
        EvaluationSampleResult result;
        try {
            EvaluationEntry entry = toEvaluationEntry(versioned);
            PredictRequest request = new PredictRequest();
            request.setModelName(run.getModelName());
            request.setText(buildPrompt(run.getTaskType(), entry));
            PredictResponse response = registry.predict(request);
            String actual = response.getText() == null ? "" : response.getText().strip();
            String score = toJson(Map.of("completed", 1.0));
            result = new EvaluationSampleResult(recovery.tenantId(), recovery.runId(), entry.getId(),
                    workIndex, SampleResultStatus.SUCCEEDED, expected, actual, score, null, null,
                    recovery.runId() + ':' + entry.getId(), Instant.now());
        } catch (RuntimeException failure) {
            result = new EvaluationSampleResult(recovery.tenantId(), recovery.runId(), versioned.data().id(),
                    workIndex, SampleResultStatus.FAILED, expected, null, null, "prediction_failed",
                    safeMessage(failure), recovery.runId() + ':' + versioned.data().id(), Instant.now());
        }
        return result;
    }

    private List<VersionedDatasetEntry> workItems(RecoveryRun recovery, DatasetSnapshot snapshot) {
        if (!recovery.retryFailedOnly()) return snapshot.entries();
        Set<String> failed = Set.copyOf(
                recoveryRepository.findFailedSampleIds(recovery.tenantId(), recovery.parentRunId()));
        return snapshot.entries().stream().filter(entry -> failed.contains(entry.data().id())).toList();
    }

    private void finishTerminal(
            RecoveryRun recovery, String ownerId, long fencingToken, RecoveryRunStatus expected,
            RecoveryRunStatus terminal, String error, long startedNanos) {
        Instant completedAt = Instant.now();
        if (!recoveryRepository.transitionToTerminal(recovery.tenantId(), recovery.runId(), expected, terminal,
                ownerId, fencingToken, error, completedAt)) return;
        runRepository.findById(recovery.runId()).ifPresent(run -> {
            run.setStatus(terminal.name().toLowerCase());
            run.setErrorMessage(error);
            run.setCompletedAt(completedAt);
            run.setElapsedSeconds((System.nanoTime() - startedNanos) / 1_000_000_000.0);
            if (terminal == RecoveryRunStatus.COMPLETED) run.setProgressPercent(100);
            saveAndPublish(run);
        });
    }

    private static EvaluationEntry toEvaluationEntry(VersionedDatasetEntry source) {
        EvaluationEntry entry = new EvaluationEntry(source.data().id(), source.data().input(),
                source.data().expectedOutput());
        entry.setLabels(source.data().labels());
        entry.setMetadata(source.data().metadata());
        return entry;
    }

    private SampleResultStatus sampleStatus(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status.strip().toLowerCase(Locale.ROOT)) {
            case "completed", "succeeded" -> SampleResultStatus.SUCCEEDED;
            case "failed" -> SampleResultStatus.FAILED;
            default -> throw new IllegalArgumentException("Unsupported evaluation sample status: " + status);
        };
    }

    private XnlpPrincipal currentPrincipal() {
        try {
            return authorization.currentPrincipal();
        } catch (org.springframework.security.access.AccessDeniedException unavailable) {
            return new XnlpPrincipal("system", TenantContext.currentTenantId(),
                    Set.of(com.xnlp.server.security.TenantRole.ADMIN), "system");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Evaluation result cannot be serialized", exception);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private void saveAndPublish(EvaluationRun run) {
        runRepository.save(run);
        progressPublisher.publish(run);
    }

    private static boolean matches(String actual, String expected) {
        return expected == null || expected.isBlank()
                || (actual != null && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)));
    }

    public CompareResult compare(List<String> runIds) {
        if (runIds == null || runIds.size() < 2) {
            throw new IllegalArgumentException("At least 2 run IDs required");
        }
        CompareResult result = new CompareResult();
        List<EvaluationRun> selected = new ArrayList<>();
        for (String id : runIds) {
            EvaluationRun r = runRepository.findById(id)
                    .orElse(null);
            if (r == null) throw new NoSuchElementException("Run not found: " + id);
            selected.add(r);
        }
        result.setRuns(selected);
        List<String> metricNames = List.of("accuracy", "f1Macro", "precisionMacro", "recallMacro",
                "rouge1", "rouge2", "rougeL", "bleu", "exactMatch", "f1Score", "entityF1");
        for (String mn : metricNames) {
            List<Double> vals = new ArrayList<>();
            for (EvaluationRun r : selected) {
                EvaluationMetrics m = r.getMetrics();
                if (m == null) { vals.add(null); continue; }
                vals.add(getMetricValue(m, mn));
            }
            if (vals.stream().anyMatch(Objects::nonNull)) {
                result.getMetricValues().put(mn, vals);
            }
        }
        for (var e : result.getMetricValues().entrySet()) {
            List<Double> vals = e.getValue();
            if (vals.get(0) == null) continue;
            List<Double> d = new ArrayList<>();
            d.add(0.0);
            for (int i = 1; i < vals.size(); i++) {
                d.add(vals.get(i) != null ? vals.get(i) - vals.get(0) : null);
            }
            result.getDeltas().put(e.getKey(), d);
        }
        String best = null;
        double bestScore = -1;
        for (EvaluationRun r : selected) {
            if (r.getMetrics() == null) continue;
            if (r.getMetrics().getAccuracy() > bestScore) {
                bestScore = r.getMetrics().getAccuracy();
                best = r.getId();
            }
        }
        result.setBestRunId(best);
        result.setSummary("Comparison of " + selected.size() + " evaluation runs.");
        return result;
    }

    private Double getMetricValue(EvaluationMetrics m, String name) {
        return switch (name) {
            case "accuracy" -> m.getAccuracy() > 0 || m.getTotalEntries() > 0 ? m.getAccuracy() : null;
            case "f1Macro" -> m.getF1Macro();
            case "precisionMacro" -> m.getPrecisionMacro();
            case "recallMacro" -> m.getRecallMacro();
            case "rouge1" -> m.getRouge1();
            case "rouge2" -> m.getRouge2();
            case "rougeL" -> m.getRougeL();
            case "bleu" -> m.getBleu();
            case "exactMatch" -> m.getExactMatch();
            case "f1Score" -> m.getF1Score();
            case "entityF1" -> m.getEntityF1();
            default -> null;
        };
    }

    private String buildPrompt(NLPTaskType task, EvaluationEntry entry) {
        String input = entry.getInput();
        return switch (task) {
            case TEXT_CLASSIFICATION -> "Classify the following text into exactly one category. "
                    + "Reply with only the category name, nothing else.\n\nText: " + input;
            case SENTIMENT_ANALYSIS -> "Analyze the sentiment of this text. "
                    + "Reply with only one word: positive, negative, or neutral.\n\nText: " + input;
            case SUMMARIZATION -> "Summarize the following text in one short sentence. "
                    + "Reply with only the summary.\n\nText: " + input;
            case QUESTION_ANSWERING -> "Answer the question based on the context. "
                    + "Reply with only the answer.\n\n" + input;
            case NAMED_ENTITY_RECOGNITION -> "Extract all named entities from the text. "
                    + "Output one entity per line as TYPE: value\n\nText: " + input;
            case TRANSLATION -> "Translate the following text to English. "
                    + "Reply with only the translation.\n\nText: " + input;
        };
    }

    private record EvaluationRequest(EvaluationRun run, RecoveryRun recovery) {
    }
}
