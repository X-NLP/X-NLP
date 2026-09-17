package com.xnlp.server.pipeline.persistence;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile("memory")
public class InMemoryPipelineRepository implements PipelineRepository {

    private final Map<DefinitionKey, PipelineDefinition> definitions = new HashMap<>();
    private final Map<VersionKey, PipelineDefinition> versions = new HashMap<>();
    private final Map<RunKey, PipelineRun> runs = new HashMap<>();
    private final Map<AttemptKey, PipelineNodeAttempt> attempts = new HashMap<>();
    private final Map<RunKey, List<PipelineRunEvent>> events = new HashMap<>();

    @Override
    public synchronized PipelineDefinition createDefinition(PipelineDefinition definition) {
        if (definition.version() != 0) throw new IllegalArgumentException("new pipeline definition must have version zero");
        DefinitionKey key = new DefinitionKey(definition.tenantId(), definition.id());
        if (definitions.putIfAbsent(key, definition) != null) {
            throw new IllegalStateException("Pipeline definition already exists");
        }
        versions.put(new VersionKey(definition.tenantId(), definition.id(), 0), definition);
        return definition;
    }

    @Override
    public synchronized PipelineWriteResult updateDefinition(PipelineDefinition requested, long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        DefinitionKey key = new DefinitionKey(requested.tenantId(), requested.id());
        PipelineDefinition current = definitions.get(key);
        if (current == null) throw new IllegalStateException("Pipeline definition does not exist");
        if (current.version() != expectedVersion) {
            return new PipelineWriteResult(PipelineWriteStatus.CONFLICT, current);
        }
        long nextVersion = expectedVersion + 1;
        PipelineDefinition updated = copyDefinition(requested, nextVersion, current.createdAt());
        definitions.put(key, updated);
        versions.put(new VersionKey(updated.tenantId(), updated.id(), nextVersion), updated);
        return new PipelineWriteResult(PipelineWriteStatus.APPLIED, updated);
    }

    @Override
    public synchronized Optional<PipelineDefinition> findDefinition(String tenantId, String pipelineId) {
        return Optional.ofNullable(definitions.get(new DefinitionKey(tenantId, pipelineId)));
    }

    @Override
    public synchronized Optional<PipelineDefinition> findDefinitionVersion(
            String tenantId, String pipelineId, long version) {
        return Optional.ofNullable(versions.get(new VersionKey(tenantId, pipelineId, version)));
    }

    @Override
    public synchronized PipelineRun createRun(PipelineRun run) {
        if (run.status() != PipelineRunStatus.QUEUED || run.revision() != 0 || run.cancelRequested()) {
            throw new IllegalArgumentException("new pipeline run must be queued at revision zero");
        }
        if (!versions.containsKey(new VersionKey(run.tenantId(), run.pipelineId(), run.pipelineVersion()))) {
            throw new IllegalStateException("Pipeline definition version does not exist");
        }
        RunKey key = new RunKey(run.tenantId(), run.runId());
        if (runs.putIfAbsent(key, run) != null) throw new IllegalStateException("Pipeline run already exists");
        events.put(key, new ArrayList<>());
        return run;
    }

    @Override
    public synchronized Optional<PipelineRun> findRun(String tenantId, String runId) {
        return Optional.ofNullable(runs.get(new RunKey(tenantId, runId)));
    }

    @Override
    public synchronized PipelineRunPage findRuns(
            String tenantId, PipelineRunStatus status, String pipelineId, int page, int size) {
        validatePage(page, size);
        String scopedTenant = PipelinePersistenceSupport.text(tenantId, "tenantId", 64);
        String scopedPipeline = pipelineId == null ? null
                : PipelinePersistenceSupport.text(pipelineId, "pipelineId", 64);
        List<PipelineRun> matches = runs.values().stream()
                .filter(run -> run.tenantId().equals(scopedTenant))
                .filter(run -> status == null || run.status() == status)
                .filter(run -> scopedPipeline == null || run.pipelineId().equals(scopedPipeline))
                .sorted(Comparator.comparing(PipelineRun::createdAt).reversed()
                        .thenComparing(PipelineRun::runId))
                .toList();
        long offset = (long) page * size;
        int from = (int) Math.min(matches.size(), offset);
        int to = (int) Math.min(matches.size(), offset + size);
        return new PipelineRunPage(matches.subList(from, to), matches.size());
    }

    @Override
    public synchronized List<PipelineRun> findRecoverableRuns() {
        return runs.values().stream()
                .filter(run -> !run.status().terminal())
                .sorted(Comparator.comparing(PipelineRun::createdAt)
                        .thenComparing(PipelineRun::tenantId).thenComparing(PipelineRun::runId))
                .toList();
    }

    @Override
    public synchronized boolean compareAndSetRunStatus(
            String tenantId, String runId, PipelineRunStatus expected, PipelineRunStatus next,
            long expectedRevision, Instant now, String outputJson, String errorCode, String errorMessage) {
        RunKey key = new RunKey(tenantId, runId);
        PipelineRun current = runs.get(key);
        if (current == null || current.status() != expected || current.revision() != expectedRevision
                || !expected.canTransitionTo(next)) return false;
        Instant startedAt = next == PipelineRunStatus.RUNNING && current.startedAt() == null
                ? now : current.startedAt();
        Instant completedAt = next.terminal() ? now : null;
        boolean cancelRequested = current.cancelRequested() || next == PipelineRunStatus.CANCELLING;
        runs.put(key, copyRun(current, next, outputJson, errorCode, errorMessage,
                cancelRequested, expectedRevision + 1, startedAt, now, completedAt));
        return true;
    }

    @Override
    public synchronized boolean requestCancellation(
            String tenantId, String runId, long expectedRevision, Instant now) {
        RunKey key = new RunKey(tenantId, runId);
        PipelineRun current = runs.get(key);
        if (current == null || current.status().terminal() || current.revision() != expectedRevision) return false;
        PipelineRunStatus next = current.status() == PipelineRunStatus.CANCELLING
                ? PipelineRunStatus.CANCELLING : PipelineRunStatus.CANCELLING;
        runs.put(key, copyRun(current, next, current.outputJson(), current.errorCode(), current.errorMessage(),
                true, expectedRevision + 1, current.startedAt(), now, null));
        return true;
    }

    @Override
    public synchronized PipelineNodeAttempt createNodeAttempt(PipelineNodeAttempt attempt) {
        PipelineRun run = runs.get(new RunKey(attempt.tenantId(), attempt.runId()));
        if (run == null || run.status().terminal() || run.cancelRequested()) {
            throw new IllegalStateException("Pipeline run cannot accept node attempts");
        }
        PipelineDefinition definition = versions.get(
                new VersionKey(run.tenantId(), run.pipelineId(), run.pipelineVersion()));
        if (definition.nodes().stream().noneMatch(node -> node.nodeId().equals(attempt.nodeId()))) {
            throw new IllegalStateException("Pipeline node does not exist in the pinned definition");
        }
        AttemptKey key = new AttemptKey(attempt.tenantId(), attempt.runId(), attempt.nodeId(), attempt.attempt());
        if (attempts.putIfAbsent(key, attempt) != null) throw new IllegalStateException("Node attempt already exists");
        return attempt;
    }

    @Override
    public synchronized boolean completeNodeAttempt(
            String tenantId, String runId, String nodeId, int attempt,
            NodeAttemptStatus expected, NodeAttemptStatus terminal,
            Instant completedAt, String outputJson, String errorCode, String errorMessage) {
        if (!terminal.terminal()) throw new IllegalArgumentException("terminal status is required");
        AttemptKey key = new AttemptKey(tenantId, runId, nodeId, attempt);
        PipelineNodeAttempt current = attempts.get(key);
        if (current == null || current.status() != expected) return false;
        PipelineRun run = runs.get(new RunKey(tenantId, runId));
        if (terminal != NodeAttemptStatus.CANCELLED
                && (run == null || run.status() != PipelineRunStatus.RUNNING || run.cancelRequested())) return false;
        attempts.put(key, new PipelineNodeAttempt(tenantId, runId, nodeId, attempt, terminal,
                current.inputJson(), outputJson, errorCode, errorMessage, current.startedAt(), completedAt));
        return true;
    }

    @Override
    public synchronized List<PipelineNodeAttempt> findNodeAttempts(String tenantId, String runId) {
        return attempts.values().stream()
                .filter(value -> value.tenantId().equals(tenantId) && value.runId().equals(runId))
                .sorted(Comparator.comparing(PipelineNodeAttempt::startedAt)
                        .thenComparing(PipelineNodeAttempt::nodeId).thenComparingInt(PipelineNodeAttempt::attempt))
                .toList();
    }

    @Override
    public synchronized PipelineRunEvent appendEvent(PipelineRunEvent requested) {
        RunKey key = new RunKey(requested.tenantId(), requested.runId());
        List<PipelineRunEvent> runEvents = events.get(key);
        if (runEvents == null) throw new IllegalStateException("Pipeline run does not exist");
        long sequence = runEvents.size() + 1L;
        PipelineRunEvent event = new PipelineRunEvent(requested.tenantId(), requested.runId(), sequence,
                requested.eventType(), requested.nodeId(), requested.attempt(), requested.payloadJson(),
                requested.occurredAt());
        runEvents.add(event);
        return event;
    }

    @Override
    public synchronized List<PipelineRunEvent> findEvents(
            String tenantId, String runId, long afterSequence, int limit) {
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return events.getOrDefault(new RunKey(tenantId, runId), List.of()).stream()
                .filter(event -> event.sequence() > afterSequence)
                .limit(limit)
                .toList();
    }

    private static void validatePage(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        if (size < 1) throw new IllegalArgumentException("size must be positive");
    }

    private static PipelineDefinition copyDefinition(
            PipelineDefinition requested, long version, Instant createdAt) {
        return new PipelineDefinition(requested.tenantId(), requested.id(), requested.name(),
                requested.description(), version, requested.nodes(), requested.edges(), requested.createdBy(),
                createdAt, requested.updatedAt());
    }

    private static PipelineRun copyRun(
            PipelineRun current, PipelineRunStatus status, String outputJson, String errorCode,
            String errorMessage, boolean cancelRequested, long revision, Instant startedAt,
            Instant updatedAt, Instant completedAt) {
        return new PipelineRun(current.tenantId(), current.runId(), current.pipelineId(),
                current.pipelineVersion(), status, current.inputJson(), outputJson, errorCode, errorMessage,
                cancelRequested, revision, current.actorId(), current.createdAt(), startedAt, updatedAt, completedAt);
    }

    private record DefinitionKey(String tenantId, String pipelineId) {
    }

    private record VersionKey(String tenantId, String pipelineId, long version) {
    }

    private record RunKey(String tenantId, String runId) {
    }

    private record AttemptKey(String tenantId, String runId, String nodeId, int attempt) {
    }
}
