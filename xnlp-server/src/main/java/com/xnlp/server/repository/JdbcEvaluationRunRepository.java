package com.xnlp.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationMetrics;
import com.xnlp.core.eval.EvaluationRun;
import com.xnlp.core.eval.NLPTaskType;
import com.xnlp.core.repository.EvaluationRunRepository;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring JDBC implementation for durable evaluation runs. */
@Repository
@Profile("!memory")
public class JdbcEvaluationRunRepository implements EvaluationRunRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    public JdbcEvaluationRunRepository(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<EvaluationRun> findAll() {
        return jdbc.query("""
                SELECT id, model_name, dataset_id, dataset_name, task_type, status, error_message,
                    metrics_json, created_at, completed_at, elapsed_seconds,
                    total_entries, processed_entries, progress_percent, cancel_requested,
                    dataset_version, parent_run_id, root_run_id, attempt_no, retry_failed_only
                FROM evaluation_runs WHERE tenant_id = ? ORDER BY created_at DESC
                """, this::mapRow, TenantContext.currentTenantId());
    }

    @Override
    public Optional<EvaluationRun> findById(String id) {
        return jdbc.query("""
                SELECT id, model_name, dataset_id, dataset_name, task_type, status, error_message,
                    metrics_json, created_at, completed_at, elapsed_seconds,
                    total_entries, processed_entries, progress_percent, cancel_requested,
                    dataset_version, parent_run_id, root_run_id, attempt_no, retry_failed_only
                FROM evaluation_runs WHERE id = ? AND tenant_id = ?
                """, this::mapRow, id, TenantContext.currentTenantId()).stream().findFirst();
    }

    @Override
    public EvaluationRun save(EvaluationRun run) {
        int updated = jdbc.update("""
                UPDATE evaluation_runs SET model_name = ?, dataset_id = ?, dataset_name = ?, task_type = ?,
                    status = ?, error_message = ?, metrics_json = ?, created_at = ?, completed_at = ?,
                    elapsed_seconds = ?, total_entries = ?, processed_entries = ?, progress_percent = ?,
                    cancel_requested = ?, dataset_version = ?, parent_run_id = ?, root_run_id = ?,
                    attempt_no = ?, retry_failed_only = ? WHERE id = ? AND tenant_id = ?
                """, run.getModelName(), run.getDatasetId(), run.getDatasetName(),
                run.getTaskType() == null ? null : run.getTaskType().name(), run.getStatus(),
                run.getErrorMessage(), toJson(run.getMetrics()), timestamp(run.getCreatedAt()),
                timestamp(run.getCompletedAt()), run.getElapsedSeconds(), run.getTotalEntries(),
                run.getProcessedEntries(), run.getProgressPercent(), run.isCancelRequested(), run.getDatasetVersion(),
                run.getParentRunId(), run.getRootRunId(), run.getAttempt(), run.isRetryFailedOnly(), run.getId(),
                TenantContext.currentTenantId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO evaluation_runs (id, tenant_id, model_name, dataset_id, dataset_name, task_type, status,
                        error_message, metrics_json, created_at, completed_at, elapsed_seconds,
                        total_entries, processed_entries, progress_percent, cancel_requested,
                        dataset_version, parent_run_id, root_run_id, attempt_no, retry_failed_only)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, run.getId(), TenantContext.currentTenantId(), run.getModelName(), run.getDatasetId(), run.getDatasetName(),
                    run.getTaskType() == null ? null : run.getTaskType().name(), run.getStatus(),
                    run.getErrorMessage(), toJson(run.getMetrics()), timestamp(run.getCreatedAt()),
                    timestamp(run.getCompletedAt()), run.getElapsedSeconds(), run.getTotalEntries(),
                    run.getProcessedEntries(), run.getProgressPercent(), run.isCancelRequested(), run.getDatasetVersion(),
                    run.getParentRunId(), run.getRootRunId(), run.getAttempt(), run.isRetryFailedOnly());
        }
        return run;
    }

    private EvaluationRun mapRow(ResultSet rs, int rowNum) throws SQLException {
        EvaluationRun run = new EvaluationRun();
        run.setId(rs.getString("id"));
        run.setModelName(rs.getString("model_name"));
        run.setDatasetId(rs.getString("dataset_id"));
        run.setDatasetName(rs.getString("dataset_name"));
        String taskType = rs.getString("task_type");
        if (taskType != null) run.setTaskType(NLPTaskType.valueOf(taskType));
        run.setStatus(rs.getString("status"));
        run.setErrorMessage(rs.getString("error_message"));
        run.setMetrics(fromJson(rs.getString("metrics_json")));
        run.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        run.setCompletedAt(toInstant(rs.getTimestamp("completed_at")));
        Double elapsed = rs.getObject("elapsed_seconds", Double.class);
        run.setElapsedSeconds(elapsed == null ? 0 : elapsed);
        Integer total = rs.getObject("total_entries", Integer.class);
        Integer processed = rs.getObject("processed_entries", Integer.class);
        Double progress = rs.getObject("progress_percent", Double.class);
        run.setTotalEntries(total == null ? 0 : total);
        run.setProcessedEntries(processed == null ? 0 : processed);
        run.setProgressPercent(progress == null ? 0 : progress);
        run.setCancelRequested(rs.getBoolean("cancel_requested"));
        Long datasetVersion = rs.getObject("dataset_version", Long.class);
        run.setDatasetVersion(datasetVersion);
        run.setParentRunId(rs.getString("parent_run_id"));
        run.setRootRunId(rs.getString("root_run_id"));
        Integer attempt = rs.getObject("attempt_no", Integer.class);
        run.setAttempt(attempt == null ? 0 : attempt);
        run.setRetryFailedOnly(rs.getBoolean("retry_failed_only"));
        return run;
    }

    private String toJson(EvaluationMetrics value) {
        if (value == null) return null;
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Evaluation metrics cannot be serialized", e);
        }
    }

    private EvaluationMetrics fromJson(String value) throws SQLException {
        if (value == null || value.isBlank()) return null;
        try {
            return jsonMapper.readValue(value, EvaluationMetrics.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid evaluation metrics JSON", e);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
