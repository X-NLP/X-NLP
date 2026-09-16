package com.xnlp.server.evaluation.recovery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EvaluationRecoveryRepository {

    RecoveryRun createRun(
            String tenantId, String runId, String datasetId, long datasetVersion,
            int totalSamples, String actorId, Instant now);

    RecoveryRun createRetry(
            String tenantId, String runId, String parentRunId,
            boolean failedSamplesOnly, String actorId, Instant now);

    Optional<RecoveryRun> findRun(String tenantId, String runId);

    List<RecoveryRun> findRetryLineage(String tenantId, String rootRunId);

    List<RecoveryRun> findRecoverableRuns();

    Optional<EvaluationCheckpoint> findCheckpoint(String tenantId, String runId);

    boolean saveCheckpoint(EvaluationCheckpoint checkpoint, String ownerId, Instant leaseCheckAt);

    Optional<EvaluationSampleResult> findSampleResult(String tenantId, String runId, String sampleId);

    List<EvaluationSampleResult> findSampleResults(String tenantId, String runId);

    List<String> findFailedSampleIds(String tenantId, String runId);

    /**
     * Persists an immutable result after verifying the active recovery lease. Repeating exactly the same
     * sample result is idempotent; reusing a sample ID or idempotency key with different content is rejected.
     */
    EvaluationSampleResult upsertSampleResult(
            EvaluationSampleResult result, String ownerId, long fencingToken, Instant leaseCheckAt);

    /** Atomically persists one immutable result and advances the durable checkpoint. */
    EvaluationSampleCommit commitSample(
            EvaluationSampleResult result, int nextSampleSequence,
            String ownerId, long fencingToken, Instant leaseCheckAt);

    Optional<RecoveryLease> findLease(String tenantId, String runId);

    /** Atomically claims an unowned or expired lease and issues a strictly increasing fencing token. */
    Optional<RecoveryLease> tryClaimLease(
            String tenantId, String runId, String ownerId, Instant acquiredAt, Instant expiresAt);

    Optional<RecoveryLease> renewLease(
            String tenantId, String runId, String ownerId, long fencingToken,
            Instant renewedAt, Instant expiresAt);

    boolean releaseLease(String tenantId, String runId, String ownerId, long fencingToken);

    boolean requestCancellation(String tenantId, String runId, Instant now);

    boolean markRunning(
            String tenantId, String runId, String ownerId, long fencingToken, Instant now);

    /** Performs a lease-fenced compare-and-set from the expected non-terminal state to a terminal state. */
    boolean transitionToTerminal(
            String tenantId, String runId, RecoveryRunStatus expectedStatus, RecoveryRunStatus terminalStatus,
            String ownerId, long fencingToken, String errorMessage, Instant completedAt);
}
