package com.xnlp.server.evaluation.recovery;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@Profile("!memory")
public class JdbcEvaluationRecoveryRepository implements EvaluationRecoveryRepository {

    private final JdbcTemplate jdbc;

    public JdbcEvaluationRecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public RecoveryRun createRun(
            String tenantId, String runId, String datasetId, long datasetVersion,
            int totalSamples, String actorId, Instant now) {
        String tenant = EvaluationRecoverySupport.tenantId(tenantId);
        String id = EvaluationRecoverySupport.text(runId, "runId", 64);
        RecoveryRun run = new RecoveryRun(tenant, id, datasetId, datasetVersion, RecoveryRunStatus.QUEUED,
                id, null, 0, false, totalSamples, actorId, null, now, now, null);
        ensureTenant(tenant, now);
        insertRun(run);
        insertControlRows(run.tenantId(), run.runId(), now);
        return run;
    }

    @Override
    @Transactional
    public RecoveryRun createRetry(
            String tenantId, String runId, String parentRunId,
            boolean failedSamplesOnly, String actorId, Instant now) {
        String tenant = EvaluationRecoverySupport.tenantId(tenantId);
        String id = EvaluationRecoverySupport.text(runId, "runId", 64);
        RecoveryRun parent = findRun(tenant, parentRunId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation run not found"));
        if (!parent.status().terminal()) throw new IllegalStateException("Parent run must be terminal");
        int total = failedSamplesOnly ? findFailedSampleIds(tenant, parent.runId()).size() : parent.totalSamples();
        RecoveryRun retry = new RecoveryRun(tenant, id, parent.datasetId(), parent.datasetVersion(),
                RecoveryRunStatus.QUEUED, parent.rootRunId(), parent.runId(), parent.attempt() + 1,
                failedSamplesOnly, total, actorId, null, now, now, null);
        insertRun(retry);
        insertControlRows(retry.tenantId(), retry.runId(), now);
        return retry;
    }

    @Override
    public Optional<RecoveryRun> findRun(String tenantId, String runId) {
        List<RecoveryRun> values = jdbc.query("""
                SELECT tenant_id, run_id, dataset_id, dataset_version, status, root_run_id, parent_run_id,
                       attempt_no, retry_failed_only, total_samples, actor_id, error_message,
                       created_at, updated_at, completed_at
                FROM evaluation_recovery_runs WHERE tenant_id = ? AND run_id = ?
                """, this::mapRun, tenant(tenantId), id(runId, "runId"));
        return values.stream().findFirst();
    }

    @Override
    public List<RecoveryRun> findRetryLineage(String tenantId, String rootRunId) {
        return jdbc.query("""
                SELECT tenant_id, run_id, dataset_id, dataset_version, status, root_run_id, parent_run_id,
                       attempt_no, retry_failed_only, total_samples, actor_id, error_message,
                       created_at, updated_at, completed_at
                FROM evaluation_recovery_runs WHERE tenant_id = ? AND root_run_id = ?
                ORDER BY attempt_no, run_id
                """, this::mapRun, tenant(tenantId), id(rootRunId, "rootRunId"));
    }

    @Override
    public List<RecoveryRun> findRecoverableRuns() {
        return jdbc.query("""
                SELECT tenant_id, run_id, dataset_id, dataset_version, status, root_run_id, parent_run_id,
                       attempt_no, retry_failed_only, total_samples, actor_id, error_message,
                       created_at, updated_at, completed_at
                FROM evaluation_recovery_runs
                WHERE status IN ('QUEUED', 'RUNNING', 'CANCELLING')
                ORDER BY created_at, tenant_id, run_id
                """, this::mapRun);
    }

    @Override
    public Optional<EvaluationCheckpoint> findCheckpoint(String tenantId, String runId) {
        List<EvaluationCheckpoint> values = jdbc.query("""
                SELECT tenant_id, run_id, next_sample_sequence, processed_samples, succeeded_samples,
                       failed_samples, last_sample_id, fencing_token, updated_at
                FROM evaluation_checkpoints WHERE tenant_id = ? AND run_id = ?
                """, this::mapCheckpoint, tenant(tenantId), id(runId, "runId"));
        return values.stream().findFirst();
    }

    @Override
    public boolean saveCheckpoint(EvaluationCheckpoint checkpoint, String ownerId, Instant leaseCheckAt) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String owner = id(ownerId, "ownerId", 190);
        Instant checkAt = EvaluationRecoverySupport.instant(leaseCheckAt, "leaseCheckAt");
        return jdbc.update("""
                UPDATE evaluation_checkpoints
                SET next_sample_sequence = ?, processed_samples = ?, succeeded_samples = ?, failed_samples = ?,
                    last_sample_id = ?, fencing_token = ?, updated_at = ?
                WHERE tenant_id = ? AND run_id = ?
                  AND next_sample_sequence <= ? AND processed_samples <= ?
                  AND EXISTS (
                    SELECT 1 FROM evaluation_recovery_leases l
                    WHERE l.tenant_id = evaluation_checkpoints.tenant_id
                      AND l.run_id = evaluation_checkpoints.run_id
                      AND l.owner_id = ? AND l.fencing_token = ? AND l.expires_at > ?
                  )
                """, checkpoint.nextSampleSequence(), checkpoint.processedSamples(), checkpoint.succeededSamples(),
                checkpoint.failedSamples(), checkpoint.lastSampleId(), checkpoint.fencingToken(),
                timestamp(checkpoint.updatedAt()), checkpoint.tenantId(), checkpoint.runId(),
                checkpoint.nextSampleSequence(), checkpoint.processedSamples(), owner,
                checkpoint.fencingToken(), timestamp(checkAt)) == 1;
    }

    @Override
    public Optional<EvaluationSampleResult> findSampleResult(String tenantId, String runId, String sampleId) {
        List<EvaluationSampleResult> values = jdbc.query("""
                SELECT tenant_id, run_id, sample_id, sample_sequence, status, expected_output, actual_output,
                       score_json, error_code, error_message, idempotency_key, completed_at
                FROM evaluation_sample_results WHERE tenant_id = ? AND run_id = ? AND sample_id = ?
                """, this::mapSample, tenant(tenantId), id(runId, "runId"), id(sampleId, "sampleId"));
        return values.stream().findFirst();
    }

    @Override
    public List<EvaluationSampleResult> findSampleResults(String tenantId, String runId) {
        return jdbc.query("""
                SELECT tenant_id, run_id, sample_id, sample_sequence, status, expected_output, actual_output,
                       score_json, error_code, error_message, idempotency_key, completed_at
                FROM evaluation_sample_results WHERE tenant_id = ? AND run_id = ?
                ORDER BY sample_sequence, sample_id
                """, this::mapSample, tenant(tenantId), id(runId, "runId"));
    }

    @Override
    public List<String> findFailedSampleIds(String tenantId, String runId) {
        return jdbc.queryForList("""
                SELECT sample_id FROM evaluation_sample_results
                WHERE tenant_id = ? AND run_id = ? AND status = 'FAILED'
                ORDER BY sample_sequence, sample_id
                """, String.class, tenant(tenantId), id(runId, "runId"));
    }

    @Override
    public EvaluationSampleResult upsertSampleResult(
            EvaluationSampleResult result, String ownerId, long fencingToken, Instant leaseCheckAt) {
        Objects.requireNonNull(result, "result");
        String owner = id(ownerId, "ownerId", 190);
        if (!ownsActiveLease(result.tenantId(), result.runId(), owner, fencingToken, leaseCheckAt)) {
            throw new IllegalStateException("Recovery lease is not active");
        }
        RecoveryRun run = findRun(result.tenantId(), result.runId())
                .orElseThrow(() -> new IllegalArgumentException("Evaluation run not found"));
        if (run.status().terminal()) throw new IllegalStateException("Terminal run cannot accept sample results");
        Optional<EvaluationSampleResult> existingBySample = findSampleResult(
                result.tenantId(), result.runId(), result.sampleId());
        if (existingBySample.isPresent()) return identical(existingBySample.get(), result, null);
        Optional<EvaluationSampleResult> existingByKey = findSampleByIdempotencyKey(
                result.tenantId(), result.runId(), result.idempotencyKey());
        if (existingByKey.isPresent()) return identical(existingByKey.get(), result, null);
        try {
            jdbc.update("""
                    INSERT INTO evaluation_sample_results
                        (tenant_id, run_id, sample_id, sample_sequence, status, expected_output, actual_output,
                         score_json, error_code, error_message, idempotency_key, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, result.tenantId(), result.runId(), result.sampleId(), result.sequence(), result.status().name(),
                    result.expectedOutput(), result.actualOutput(), result.scoreJson(), result.errorCode(),
                    result.errorMessage(), result.idempotencyKey(), timestamp(result.completedAt()));
            return result;
        } catch (DuplicateKeyException duplicate) {
            Optional<EvaluationSampleResult> existing = findSampleResult(
                    result.tenantId(), result.runId(), result.sampleId());
            if (existing.isEmpty()) {
                existing = findSampleByIdempotencyKey(result.tenantId(), result.runId(), result.idempotencyKey());
            }
            if (existing.isPresent()) return identical(existing.get(), result, duplicate);
            throw new IllegalStateException("Sample result idempotency conflict", duplicate);
        }
    }

    @Override
    @Transactional
    public EvaluationSampleCommit commitSample(
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
    public Optional<RecoveryLease> findLease(String tenantId, String runId) {
        List<RecoveryLease> values = jdbc.query("""
                SELECT tenant_id, run_id, owner_id, fencing_token, acquired_at, renewed_at, expires_at
                FROM evaluation_recovery_leases
                WHERE tenant_id = ? AND run_id = ? AND owner_id IS NOT NULL
                """, this::mapLease, tenant(tenantId), id(runId, "runId"));
        return values.stream().findFirst();
    }

    @Override
    public Optional<RecoveryLease> tryClaimLease(
            String tenantId, String runId, String ownerId, Instant acquiredAt, Instant expiresAt) {
        String tenant = tenant(tenantId);
        String run = id(runId, "runId");
        String owner = id(ownerId, "ownerId", 190);
        validateLeaseTimes(acquiredAt, expiresAt);
        int claimed = jdbc.update("""
                UPDATE evaluation_recovery_leases
                SET owner_id = ?, fencing_token = fencing_token + 1,
                    acquired_at = ?, renewed_at = ?, expires_at = ?
                WHERE tenant_id = ? AND run_id = ?
                  AND (owner_id IS NULL OR expires_at <= ?)
                  AND EXISTS (
                    SELECT 1 FROM evaluation_recovery_runs r
                    WHERE r.tenant_id = evaluation_recovery_leases.tenant_id
                      AND r.run_id = evaluation_recovery_leases.run_id
                      AND r.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                  )
                """, owner, timestamp(acquiredAt), timestamp(acquiredAt), timestamp(expiresAt),
                tenant, run, timestamp(acquiredAt));
        if (claimed == 1) return findLease(tenant, run);
        return findLease(tenant, run).filter(lease -> lease.ownerId().equals(owner) && lease.activeAt(acquiredAt));
    }

    @Override
    public Optional<RecoveryLease> renewLease(
            String tenantId, String runId, String ownerId, long fencingToken,
            Instant renewedAt, Instant expiresAt) {
        String tenant = tenant(tenantId);
        String run = id(runId, "runId");
        String owner = id(ownerId, "ownerId", 190);
        validateLeaseTimes(renewedAt, expiresAt);
        int updated = jdbc.update("""
                UPDATE evaluation_recovery_leases SET renewed_at = ?, expires_at = ?
                WHERE tenant_id = ? AND run_id = ? AND owner_id = ? AND fencing_token = ? AND expires_at > ?
                """, timestamp(renewedAt), timestamp(expiresAt), tenant, run, owner,
                positiveToken(fencingToken), timestamp(renewedAt));
        return updated == 1 ? findLease(tenant, run) : Optional.empty();
    }

    @Override
    public boolean releaseLease(String tenantId, String runId, String ownerId, long fencingToken) {
        return jdbc.update("""
                UPDATE evaluation_recovery_leases
                SET owner_id = NULL, acquired_at = NULL, renewed_at = NULL, expires_at = NULL
                WHERE tenant_id = ? AND run_id = ? AND owner_id = ? AND fencing_token = ?
                """, tenant(tenantId), id(runId, "runId"), id(ownerId, "ownerId", 190),
                positiveToken(fencingToken)) == 1;
    }

    @Override
    public boolean requestCancellation(String tenantId, String runId, Instant now) {
        String tenant = tenant(tenantId);
        String id = id(runId, "runId");
        int updated = jdbc.update("""
                UPDATE evaluation_recovery_runs
                SET status = 'CANCELLING', updated_at = ?
                WHERE tenant_id = ? AND run_id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                """, timestamp(now), tenant, id);
        if (updated == 1) return true;
        return findRun(tenant, id).map(run -> run.status().terminal()
                || run.status() == RecoveryRunStatus.CANCELLING).orElse(false);
    }

    @Override
    public boolean markRunning(
            String tenantId, String runId, String ownerId, long fencingToken, Instant now) {
        String tenant = tenant(tenantId);
        String run = id(runId, "runId");
        String owner = id(ownerId, "ownerId", 190);
        long token = positiveToken(fencingToken);
        Instant started = EvaluationRecoverySupport.instant(now, "now");
        int updated = jdbc.update("""
                UPDATE evaluation_recovery_runs SET status = 'RUNNING', updated_at = ?
                WHERE tenant_id = ? AND run_id = ? AND status = 'QUEUED'
                  AND EXISTS (
                    SELECT 1 FROM evaluation_recovery_leases l
                    WHERE l.tenant_id = evaluation_recovery_runs.tenant_id
                      AND l.run_id = evaluation_recovery_runs.run_id
                      AND l.owner_id = ? AND l.fencing_token = ? AND l.expires_at > ?
                  )
                """, timestamp(started), tenant, run, owner, token, timestamp(started));
        if (updated == 1) return true;
        return findRun(tenant, run).map(RecoveryRun::status).orElse(null) == RecoveryRunStatus.RUNNING
                && ownsActiveLease(tenant, run, owner, token, started);
    }

    @Override
    public boolean transitionToTerminal(
            String tenantId, String runId, RecoveryRunStatus expectedStatus, RecoveryRunStatus terminalStatus,
            String ownerId, long fencingToken, String errorMessage, Instant completedAt) {
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (expectedStatus.terminal()) throw new IllegalArgumentException("expectedStatus must not be terminal");
        if (!terminalStatus.terminal()) throw new IllegalArgumentException("terminalStatus must be terminal");
        String normalizedError = EvaluationRecoverySupport.nullableText(errorMessage, "errorMessage", 65535);
        if (terminalStatus != RecoveryRunStatus.FAILED && normalizedError != null) {
            throw new IllegalArgumentException("errorMessage is only valid for failed runs");
        }
        Instant completed = EvaluationRecoverySupport.instant(completedAt, "completedAt");
        return jdbc.update("""
                UPDATE evaluation_recovery_runs
                SET status = ?, error_message = ?, completed_at = ?, updated_at = ?
                WHERE tenant_id = ? AND run_id = ? AND status = ?
                  AND EXISTS (
                    SELECT 1 FROM evaluation_recovery_leases l
                    WHERE l.tenant_id = evaluation_recovery_runs.tenant_id
                      AND l.run_id = evaluation_recovery_runs.run_id
                      AND l.owner_id = ? AND l.fencing_token = ? AND l.expires_at > ?
                  )
                """, terminalStatus.name(), normalizedError, timestamp(completed), timestamp(completed),
                tenant(tenantId), id(runId, "runId"), expectedStatus.name(), id(ownerId, "ownerId", 190),
                positiveToken(fencingToken), timestamp(completed)) == 1;
    }

    private void insertRun(RecoveryRun run) {
        jdbc.update("""
                INSERT INTO evaluation_recovery_runs
                    (tenant_id, run_id, dataset_id, dataset_version, status, root_run_id, parent_run_id,
                     attempt_no, retry_failed_only, total_samples, actor_id, error_message,
                     created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.tenantId(), run.runId(), run.datasetId(), run.datasetVersion(), run.status().name(),
                run.rootRunId(), run.parentRunId(), run.attempt(), run.retryFailedOnly(), run.totalSamples(),
                run.actorId(), run.errorMessage(), timestamp(run.createdAt()), timestamp(run.updatedAt()),
                nullableTimestamp(run.completedAt()));
    }

    private void insertControlRows(String tenantId, String runId, Instant now) {
        jdbc.update("""
                INSERT INTO evaluation_checkpoints
                    (tenant_id, run_id, next_sample_sequence, processed_samples, succeeded_samples,
                     failed_samples, last_sample_id, fencing_token, updated_at)
                VALUES (?, ?, 0, 0, 0, 0, NULL, 0, ?)
                """, tenantId, runId, timestamp(now));
        jdbc.update("""
                INSERT INTO evaluation_recovery_leases
                    (tenant_id, run_id, owner_id, fencing_token, acquired_at, renewed_at, expires_at)
                VALUES (?, ?, NULL, 0, NULL, NULL, NULL)
                """, tenantId, runId);
    }

    private Optional<EvaluationSampleResult> findSampleByIdempotencyKey(
            String tenantId, String runId, String idempotencyKey) {
        List<EvaluationSampleResult> values = jdbc.query("""
                SELECT tenant_id, run_id, sample_id, sample_sequence, status, expected_output, actual_output,
                       score_json, error_code, error_message, idempotency_key, completed_at
                FROM evaluation_sample_results
                WHERE tenant_id = ? AND run_id = ? AND idempotency_key = ?
                """, this::mapSample, tenantId, runId, idempotencyKey);
        return values.stream().findFirst();
    }

    private static EvaluationSampleResult identical(
            EvaluationSampleResult existing, EvaluationSampleResult requested, RuntimeException cause) {
        if (existing.equals(requested)) return existing;
        if (cause == null) throw new IllegalStateException("Sample result idempotency conflict");
        throw new IllegalStateException("Sample result idempotency conflict", cause);
    }

    private boolean ownsActiveLease(
            String tenantId, String runId, String ownerId, long fencingToken, Instant at) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM evaluation_recovery_leases
                WHERE tenant_id = ? AND run_id = ? AND owner_id = ? AND fencing_token = ? AND expires_at > ?
                """, Integer.class, tenantId, runId, ownerId, positiveToken(fencingToken), timestamp(at));
        return count != null && count == 1;
    }

    private void ensureTenant(String tenantId, Instant now) {
        try {
            jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    tenantId, tenantId, timestamp(now), timestamp(now));
        } catch (DuplicateKeyException ignored) {
            // Tenant already exists.
        }
    }

    private RecoveryRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RecoveryRun(rs.getString("tenant_id"), rs.getString("run_id"), rs.getString("dataset_id"),
                rs.getLong("dataset_version"), RecoveryRunStatus.valueOf(rs.getString("status")),
                rs.getString("root_run_id"), rs.getString("parent_run_id"), rs.getInt("attempt_no"),
                rs.getBoolean("retry_failed_only"), rs.getInt("total_samples"), rs.getString("actor_id"),
                rs.getString("error_message"), instant(rs, "created_at"), instant(rs, "updated_at"),
                nullableInstant(rs, "completed_at"));
    }

    private EvaluationCheckpoint mapCheckpoint(ResultSet rs, int rowNum) throws SQLException {
        return new EvaluationCheckpoint(rs.getString("tenant_id"), rs.getString("run_id"),
                rs.getInt("next_sample_sequence"), rs.getInt("processed_samples"),
                rs.getInt("succeeded_samples"), rs.getInt("failed_samples"), rs.getString("last_sample_id"),
                rs.getLong("fencing_token"), instant(rs, "updated_at"));
    }

    private EvaluationSampleResult mapSample(ResultSet rs, int rowNum) throws SQLException {
        return new EvaluationSampleResult(rs.getString("tenant_id"), rs.getString("run_id"),
                rs.getString("sample_id"), rs.getInt("sample_sequence"),
                SampleResultStatus.valueOf(rs.getString("status")), rs.getString("expected_output"),
                rs.getString("actual_output"), rs.getString("score_json"), rs.getString("error_code"),
                rs.getString("error_message"), rs.getString("idempotency_key"), instant(rs, "completed_at"));
    }

    private RecoveryLease mapLease(ResultSet rs, int rowNum) throws SQLException {
        return new RecoveryLease(rs.getString("tenant_id"), rs.getString("run_id"), rs.getString("owner_id"),
                rs.getLong("fencing_token"), instant(rs, "acquired_at"), instant(rs, "renewed_at"),
                instant(rs, "expires_at"));
    }

    private static String tenant(String value) {
        return EvaluationRecoverySupport.tenantId(value);
    }

    private static String id(String value, String field) {
        return EvaluationRecoverySupport.text(value, field, 64);
    }

    private static String id(String value, String field, int maximumLength) {
        return EvaluationRecoverySupport.text(value, field, maximumLength);
    }

    private static long positiveToken(long token) {
        if (token < 1) throw new IllegalArgumentException("fencingToken must be positive");
        return token;
    }

    private static void validateLeaseTimes(Instant startedAt, Instant expiresAt) {
        EvaluationRecoverySupport.instant(startedAt, "startedAt");
        EvaluationRecoverySupport.instant(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(startedAt)) throw new IllegalArgumentException("expiresAt must be after start time");
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(EvaluationRecoverySupport.instant(value, "instant"));
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
