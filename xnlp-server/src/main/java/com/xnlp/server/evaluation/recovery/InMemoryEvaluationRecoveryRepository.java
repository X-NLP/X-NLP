package com.xnlp.server.evaluation.recovery;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
@Profile("memory")
public class InMemoryEvaluationRecoveryRepository implements EvaluationRecoveryRepository {

    private final Map<RunKey, RecoveryRun> runs = new HashMap<>();
    private final Map<RunKey, EvaluationCheckpoint> checkpoints = new HashMap<>();
    private final Map<SampleKey, EvaluationSampleResult> samples = new HashMap<>();
    private final Map<RunKey, RecoveryLease> leases = new HashMap<>();
    private final Map<RunKey, Long> lastTokens = new HashMap<>();

    @Override
    public synchronized RecoveryRun createRun(
            String tenantId, String runId, String datasetId, long datasetVersion,
            int totalSamples, String actorId, Instant now) {
        String tenant = EvaluationRecoverySupport.tenantId(tenantId);
        String id = EvaluationRecoverySupport.text(runId, "runId", 64);
        RecoveryRun run = new RecoveryRun(tenant, id, datasetId, datasetVersion, RecoveryRunStatus.QUEUED,
                id, null, 0, false, totalSamples, actorId, null, now, now, null);
        RunKey key = new RunKey(tenant, id);
        if (runs.putIfAbsent(key, run) != null) throw new IllegalStateException("Evaluation run already exists");
        checkpoints.put(key, emptyCheckpoint(tenant, id, now));
        return run;
    }

    @Override
    public synchronized RecoveryRun createRetry(
            String tenantId, String runId, String parentRunId,
            boolean failedSamplesOnly, String actorId, Instant now) {
        String tenant = EvaluationRecoverySupport.tenantId(tenantId);
        String id = EvaluationRecoverySupport.text(runId, "runId", 64);
        RecoveryRun parent = requireRun(tenant, parentRunId);
        if (!parent.status().terminal()) throw new IllegalStateException("Parent run must be terminal");
        int total = failedSamplesOnly ? findFailedSampleIds(tenant, parent.runId()).size() : parent.totalSamples();
        RecoveryRun retry = new RecoveryRun(tenant, id, parent.datasetId(), parent.datasetVersion(),
                RecoveryRunStatus.QUEUED, parent.rootRunId(), parent.runId(), parent.attempt() + 1,
                failedSamplesOnly, total, actorId, null, now, now, null);
        RunKey key = new RunKey(tenant, id);
        if (runs.putIfAbsent(key, retry) != null) throw new IllegalStateException("Evaluation run already exists");
        checkpoints.put(key, emptyCheckpoint(tenant, id, now));
        return retry;
    }

    @Override
    public synchronized Optional<RecoveryRun> findRun(String tenantId, String runId) {
        return Optional.ofNullable(runs.get(runKey(tenantId, runId)));
    }

    @Override
    public synchronized List<RecoveryRun> findRetryLineage(String tenantId, String rootRunId) {
        String tenant = EvaluationRecoverySupport.tenantId(tenantId);
        String root = EvaluationRecoverySupport.text(rootRunId, "rootRunId", 64);
        return runs.values().stream()
                .filter(run -> run.tenantId().equals(tenant) && run.rootRunId().equals(root))
                .sorted(Comparator.comparingInt(RecoveryRun::attempt).thenComparing(RecoveryRun::runId))
                .toList();
    }

    @Override
    public synchronized List<RecoveryRun> findRecoverableRuns() {
        return runs.values().stream()
                .filter(run -> !run.status().terminal())
                .sorted(Comparator.comparing(RecoveryRun::createdAt).thenComparing(RecoveryRun::runId))
                .toList();
    }

    @Override
    public synchronized Optional<EvaluationCheckpoint> findCheckpoint(String tenantId, String runId) {
        return Optional.ofNullable(checkpoints.get(runKey(tenantId, runId)));
    }

    @Override
    public synchronized boolean saveCheckpoint(EvaluationCheckpoint checkpoint, String ownerId, Instant leaseCheckAt) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String owner = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        Instant checkAt = EvaluationRecoverySupport.instant(leaseCheckAt, "leaseCheckAt");
        RunKey key = new RunKey(checkpoint.tenantId(), checkpoint.runId());
        if (!hasLease(key, owner, checkpoint.fencingToken(), checkAt)) return false;
        if (!runs.containsKey(key)) return false;
        EvaluationCheckpoint current = checkpoints.get(key);
        if (current != null && (checkpoint.processedSamples() < current.processedSamples()
                || checkpoint.nextSampleSequence() < current.nextSampleSequence())) {
            return false;
        }
        checkpoints.put(key, checkpoint);
        return true;
    }

    @Override
    public synchronized Optional<EvaluationSampleResult> findSampleResult(
            String tenantId, String runId, String sampleId) {
        RunKey run = runKey(tenantId, runId);
        return Optional.ofNullable(samples.get(new SampleKey(run, normalizeSampleId(sampleId))));
    }

    @Override
    public synchronized List<EvaluationSampleResult> findSampleResults(String tenantId, String runId) {
        RunKey key = runKey(tenantId, runId);
        return samples.entrySet().stream().filter(entry -> entry.getKey().run().equals(key))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparingInt(EvaluationSampleResult::sequence)
                        .thenComparing(EvaluationSampleResult::sampleId))
                .toList();
    }

    @Override
    public synchronized List<String> findFailedSampleIds(String tenantId, String runId) {
        return findSampleResults(tenantId, runId).stream()
                .filter(result -> result.status() == SampleResultStatus.FAILED)
                .map(EvaluationSampleResult::sampleId).toList();
    }

    @Override
    public synchronized EvaluationSampleResult upsertSampleResult(
            EvaluationSampleResult result, String ownerId, long fencingToken, Instant leaseCheckAt) {
        Objects.requireNonNull(result, "result");
        String owner = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        Instant checkAt = EvaluationRecoverySupport.instant(leaseCheckAt, "leaseCheckAt");
        RunKey runKey = new RunKey(result.tenantId(), result.runId());
        if (!hasLease(runKey, owner, fencingToken, checkAt)) throw new IllegalStateException("Recovery lease is not active");
        RecoveryRun run = requireRun(runKey.tenantId(), runKey.runId());
        if (run.status().terminal()) throw new IllegalStateException("Terminal run cannot accept sample results");
        SampleKey key = new SampleKey(runKey, result.sampleId());
        EvaluationSampleResult existing = samples.get(key);
        if (existing != null) return identical(existing, result);
        Optional<EvaluationSampleResult> duplicateKey = samples.entrySet().stream()
                .filter(entry -> entry.getKey().run().equals(runKey))
                .map(Map.Entry::getValue)
                .filter(value -> value.idempotencyKey().equals(result.idempotencyKey()))
                .findFirst();
        if (duplicateKey.isPresent()) return identical(duplicateKey.get(), result);
        samples.put(key, result);
        return result;
    }

    @Override
    public synchronized EvaluationSampleCommit commitSample(
            EvaluationSampleResult result, int nextSampleSequence,
            String ownerId, long fencingToken, Instant leaseCheckAt) {
        EvaluationSampleResult stored = upsertSampleResult(result, ownerId, fencingToken, leaseCheckAt);
        List<EvaluationSampleResult> values = findSampleResults(result.tenantId(), result.runId());
        int succeeded = (int) values.stream()
                .filter(value -> value.status() == SampleResultStatus.SUCCEEDED).count();
        int failed = values.size() - succeeded;
        EvaluationCheckpoint checkpoint = new EvaluationCheckpoint(
                result.tenantId(), result.runId(), nextSampleSequence, values.size(), succeeded, failed,
                stored.sampleId(), fencingToken, leaseCheckAt);
        if (!saveCheckpoint(checkpoint, ownerId, leaseCheckAt)) {
            throw new IllegalStateException("Evaluation checkpoint lost its recovery lease");
        }
        return new EvaluationSampleCommit(stored, checkpoint);
    }

    @Override
    public synchronized Optional<RecoveryLease> findLease(String tenantId, String runId) {
        return Optional.ofNullable(leases.get(runKey(tenantId, runId)));
    }

    @Override
    public synchronized Optional<RecoveryLease> tryClaimLease(
            String tenantId, String runId, String ownerId, Instant acquiredAt, Instant expiresAt) {
        RunKey key = runKey(tenantId, runId);
        String owner = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        validateLeaseTimes(acquiredAt, expiresAt);
        RecoveryRun run = runs.get(key);
        if (run == null || run.status().terminal()) return Optional.empty();
        RecoveryLease existing = leases.get(key);
        if (existing != null && existing.activeAt(acquiredAt)) {
            return existing.ownerId().equals(owner) ? Optional.of(existing) : Optional.empty();
        }
        long token = Math.addExact(lastTokens.getOrDefault(key, 0L), 1L);
        RecoveryLease claimed = new RecoveryLease(key.tenantId(), key.runId(), owner, token,
                acquiredAt, acquiredAt, expiresAt);
        lastTokens.put(key, token);
        leases.put(key, claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized Optional<RecoveryLease> renewLease(
            String tenantId, String runId, String ownerId, long fencingToken,
            Instant renewedAt, Instant expiresAt) {
        RunKey key = runKey(tenantId, runId);
        String owner = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        validateLeaseTimes(renewedAt, expiresAt);
        RecoveryLease existing = leases.get(key);
        if (existing == null || !existing.ownerId().equals(owner) || existing.fencingToken() != fencingToken
                || !existing.activeAt(renewedAt)) return Optional.empty();
        RecoveryLease renewed = new RecoveryLease(existing.tenantId(), existing.runId(), owner, fencingToken,
                existing.acquiredAt(), renewedAt, expiresAt);
        leases.put(key, renewed);
        return Optional.of(renewed);
    }

    @Override
    public synchronized boolean releaseLease(String tenantId, String runId, String ownerId, long fencingToken) {
        RunKey key = runKey(tenantId, runId);
        String owner = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        RecoveryLease existing = leases.get(key);
        if (existing == null || !existing.ownerId().equals(owner) || existing.fencingToken() != fencingToken) {
            return false;
        }
        leases.remove(key);
        return true;
    }

    @Override
    public synchronized boolean requestCancellation(String tenantId, String runId, Instant now) {
        RunKey key = runKey(tenantId, runId);
        RecoveryRun run = runs.get(key);
        if (run == null) return false;
        if (run.status().terminal() || run.status() == RecoveryRunStatus.CANCELLING) return true;
        runs.put(key, copyStatus(run, RecoveryRunStatus.CANCELLING, null, now, null));
        return true;
    }

    @Override
    public synchronized boolean markRunning(
            String tenantId, String runId, String ownerId, long fencingToken, Instant now) {
        RunKey key = runKey(tenantId, runId);
        String owner = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        if (!hasLease(key, owner, fencingToken, now)) return false;
        RecoveryRun run = runs.get(key);
        if (run == null) return false;
        if (run.status() == RecoveryRunStatus.RUNNING) return true;
        if (run.status() != RecoveryRunStatus.QUEUED) return false;
        runs.put(key, copyStatus(run, RecoveryRunStatus.RUNNING, null, now, null));
        return true;
    }

    @Override
    public synchronized boolean transitionToTerminal(
            String tenantId, String runId, RecoveryRunStatus expectedStatus, RecoveryRunStatus terminalStatus,
            String ownerId, long fencingToken, String errorMessage, Instant completedAt) {
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (expectedStatus.terminal()) throw new IllegalArgumentException("expectedStatus must not be terminal");
        if (!terminalStatus.terminal()) throw new IllegalArgumentException("terminalStatus must be terminal");
        RunKey key = runKey(tenantId, runId);
        String owner = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        if (!hasLease(key, owner, fencingToken, completedAt)) return false;
        RecoveryRun run = runs.get(key);
        if (run == null || run.status() != expectedStatus) return false;
        runs.put(key, copyStatus(run, terminalStatus, errorMessage, completedAt, completedAt));
        return true;
    }

    private RecoveryRun requireRun(String tenantId, String runId) {
        return Optional.ofNullable(runs.get(runKey(tenantId, runId)))
                .orElseThrow(() -> new IllegalArgumentException("Evaluation run not found"));
    }

    private boolean hasLease(RunKey key, String ownerId, long fencingToken, Instant at) {
        RecoveryLease lease = leases.get(key);
        return lease != null && lease.ownerId().equals(ownerId) && lease.fencingToken() == fencingToken
                && lease.activeAt(at);
    }

    private static EvaluationSampleResult identical(
            EvaluationSampleResult existing, EvaluationSampleResult requested) {
        if (!existing.equals(requested)) throw new IllegalStateException("Sample result idempotency conflict");
        return existing;
    }

    private static EvaluationCheckpoint emptyCheckpoint(String tenantId, String runId, Instant now) {
        return new EvaluationCheckpoint(tenantId, runId, 0, 0, 0, 0, null, 0, now);
    }

    private static RecoveryRun copyStatus(
            RecoveryRun run, RecoveryRunStatus status, String errorMessage, Instant updatedAt, Instant completedAt) {
        return new RecoveryRun(run.tenantId(), run.runId(), run.datasetId(), run.datasetVersion(), status,
                run.rootRunId(), run.parentRunId(), run.attempt(), run.retryFailedOnly(), run.totalSamples(),
                run.actorId(), errorMessage, run.createdAt(), updatedAt, completedAt);
    }

    private static void validateLeaseTimes(Instant startedAt, Instant expiresAt) {
        EvaluationRecoverySupport.instant(startedAt, "startedAt");
        EvaluationRecoverySupport.instant(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(startedAt)) throw new IllegalArgumentException("expiresAt must be after start time");
    }

    private static String normalizeSampleId(String sampleId) {
        return EvaluationRecoverySupport.text(sampleId, "sampleId", 64);
    }

    private static RunKey runKey(String tenantId, String runId) {
        return new RunKey(EvaluationRecoverySupport.tenantId(tenantId),
                EvaluationRecoverySupport.text(runId, "runId", 64));
    }

    private record RunKey(String tenantId, String runId) {
    }

    private record SampleKey(RunKey run, String sampleId) {
    }
}
