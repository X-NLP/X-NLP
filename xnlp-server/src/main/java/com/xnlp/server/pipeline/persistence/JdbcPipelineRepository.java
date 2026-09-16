package com.xnlp.server.pipeline.persistence;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!memory")
public class JdbcPipelineRepository implements PipelineRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcPipelineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) throw new IllegalArgumentException("JdbcTemplate requires a DataSource");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public PipelineDefinition createDefinition(PipelineDefinition definition) {
        if (definition.version() != 0) throw new IllegalArgumentException("new pipeline definition must have version zero");
        return transaction.execute(status -> {
            ensureTenant(definition.tenantId(), definition.createdAt());
            jdbc.update("""
                    INSERT INTO pipeline_definitions
                    (tenant_id, id, name, description, version, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, definition.tenantId(), definition.id(), definition.name(), definition.description(),
                    definition.version(), definition.createdBy(), timestamp(definition.createdAt()),
                    timestamp(definition.updatedAt()));
            insertDefinitionVersion(definition);
            return definition;
        });
    }

    @Override
    public PipelineWriteResult updateDefinition(PipelineDefinition requested, long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        return transaction.execute(status -> {
            Optional<PipelineDefinition> existing = findDefinition(requested.tenantId(), requested.id());
            if (existing.isEmpty()) throw new IllegalStateException("Pipeline definition does not exist");
            PipelineDefinition current = existing.orElseThrow();
            if (current.version() != expectedVersion) {
                return new PipelineWriteResult(PipelineWriteStatus.CONFLICT, current);
            }
            long nextVersion = expectedVersion + 1;
            int updated = jdbc.update("""
                    UPDATE pipeline_definitions
                    SET name = ?, description = ?, version = ?, updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND version = ?
                    """, requested.name(), requested.description(), nextVersion, timestamp(requested.updatedAt()),
                    requested.tenantId(), requested.id(), expectedVersion);
            if (updated != 1) {
                PipelineDefinition winner = findDefinition(requested.tenantId(), requested.id())
                        .orElseThrow(() -> new IllegalStateException("Pipeline definition disappeared during update"));
                return new PipelineWriteResult(PipelineWriteStatus.CONFLICT, winner);
            }
            PipelineDefinition applied = new PipelineDefinition(
                    requested.tenantId(), requested.id(), requested.name(), requested.description(), nextVersion,
                    requested.nodes(), requested.edges(), current.createdBy(), current.createdAt(), requested.updatedAt());
            insertDefinitionVersion(applied);
            return new PipelineWriteResult(PipelineWriteStatus.APPLIED, applied);
        });
    }

    @Override
    public Optional<PipelineDefinition> findDefinition(String tenantId, String pipelineId) {
        List<DefinitionMetadata> rows = jdbc.query("""
                SELECT tenant_id, id, name, description, version, created_by, created_at, updated_at
                FROM pipeline_definitions WHERE tenant_id = ? AND id = ?
                """, this::mapDefinitionMetadata, tenant(tenantId), id(pipelineId, "pipelineId", 64));
        return rows.stream().findFirst().map(this::loadDefinition);
    }

    @Override
    public Optional<PipelineDefinition> findDefinitionVersion(String tenantId, String pipelineId, long version) {
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        List<DefinitionMetadata> rows = jdbc.query("""
                SELECT v.tenant_id, v.pipeline_id AS id, v.name, v.description, v.version,
                       v.created_by, d.created_at, v.created_at AS updated_at
                FROM pipeline_definition_versions v
                JOIN pipeline_definitions d ON d.tenant_id = v.tenant_id AND d.id = v.pipeline_id
                WHERE v.tenant_id = ? AND v.pipeline_id = ? AND v.version = ?
                """, this::mapDefinitionMetadata, tenant(tenantId), id(pipelineId, "pipelineId", 64), version);
        return rows.stream().findFirst().map(this::loadDefinition);
    }

    @Override
    public PipelineRun createRun(PipelineRun run) {
        if (run.status() != PipelineRunStatus.QUEUED || run.revision() != 0 || run.cancelRequested()) {
            throw new IllegalArgumentException("new pipeline run must be queued at revision zero");
        }
        return transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO pipeline_runs
                    (tenant_id, run_id, pipeline_id, pipeline_version, status, input_json, output_json,
                     error_code, error_message, cancel_requested, revision, actor_id, created_at,
                     started_at, updated_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, run.tenantId(), run.runId(), run.pipelineId(), run.pipelineVersion(), run.status().name(),
                    run.inputJson(), run.outputJson(), run.errorCode(), run.errorMessage(), run.cancelRequested(),
                    run.revision(), run.actorId(), timestamp(run.createdAt()), nullableTimestamp(run.startedAt()),
                    timestamp(run.updatedAt()), nullableTimestamp(run.completedAt()));
            jdbc.update("""
                    INSERT INTO pipeline_run_event_sequences (tenant_id, run_id, next_sequence)
                    VALUES (?, ?, 1)
                    """, run.tenantId(), run.runId());
            return run;
        });
    }

    @Override
    public Optional<PipelineRun> findRun(String tenantId, String runId) {
        List<PipelineRun> rows = jdbc.query("""
                SELECT * FROM pipeline_runs WHERE tenant_id = ? AND run_id = ?
                """, this::mapRun, tenant(tenantId), id(runId, "runId", 64));
        return rows.stream().findFirst();
    }

    @Override
    public List<PipelineRun> findRecoverableRuns() {
        return jdbc.query("""
                SELECT * FROM pipeline_runs
                WHERE status IN ('QUEUED', 'RUNNING', 'CANCELLING')
                ORDER BY created_at, tenant_id, run_id
                """, this::mapRun);
    }

    @Override
    public boolean compareAndSetRunStatus(
            String tenantId, String runId, PipelineRunStatus expected, PipelineRunStatus next,
            long expectedRevision, Instant now, String outputJson, String errorCode, String errorMessage) {
        if (!expected.canTransitionTo(next)) return false;
        PipelinePersistenceSupport.instant(now, "now");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        Timestamp completedAt = next.terminal() ? timestamp(now) : null;
        Timestamp startedAt = next == PipelineRunStatus.RUNNING ? timestamp(now) : null;
        boolean requestingCancel = next == PipelineRunStatus.CANCELLING;
        String cancellationGuard = next.terminal() && expected == PipelineRunStatus.CANCELLING
                ? " AND cancel_requested = TRUE" : requestingCancel ? "" : " AND cancel_requested = FALSE";
        int updated = jdbc.update("""
                UPDATE pipeline_runs
                SET status = ?, output_json = ?, error_code = ?, error_message = ?,
                    cancel_requested = CASE WHEN ? THEN TRUE ELSE cancel_requested END,
                    revision = revision + 1,
                    started_at = CASE WHEN ? IS NOT NULL AND started_at IS NULL THEN ? ELSE started_at END,
                    updated_at = ?, completed_at = ?
                WHERE tenant_id = ? AND run_id = ? AND status = ? AND revision = ?
                """ + cancellationGuard,
                next.name(), outputJson, errorCode, errorMessage, requestingCancel,
                startedAt, startedAt, timestamp(now), completedAt,
                tenant(tenantId), id(runId, "runId", 64), expected.name(), expectedRevision);
        return updated == 1;
    }

    @Override
    public boolean requestCancellation(String tenantId, String runId, long expectedRevision, Instant now) {
        PipelinePersistenceSupport.instant(now, "now");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        return jdbc.update("""
                UPDATE pipeline_runs
                SET status = 'CANCELLING', cancel_requested = TRUE, revision = revision + 1, updated_at = ?
                WHERE tenant_id = ? AND run_id = ? AND revision = ?
                  AND status IN ('QUEUED', 'RUNNING') AND cancel_requested = FALSE
                """, timestamp(now), tenant(tenantId), id(runId, "runId", 64), expectedRevision) == 1;
    }

    @Override
    public PipelineNodeAttempt createNodeAttempt(PipelineNodeAttempt attempt) {
        if (attempt.status() != NodeAttemptStatus.RUNNING) {
            throw new IllegalArgumentException("new node attempt must be running");
        }
        int inserted = jdbc.update("""
                INSERT INTO pipeline_node_attempts
                (tenant_id, run_id, node_id, attempt_no, status, input_json, output_json,
                 error_code, error_message, started_at, completed_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE EXISTS (
                    SELECT 1 FROM pipeline_runs r
                    JOIN pipeline_nodes n
                      ON n.tenant_id = r.tenant_id AND n.pipeline_id = r.pipeline_id
                     AND n.pipeline_version = r.pipeline_version AND n.node_id = ?
                    WHERE r.tenant_id = ? AND r.run_id = ?
                      AND r.status IN ('QUEUED', 'RUNNING') AND r.cancel_requested = FALSE
                )
                """, attempt.tenantId(), attempt.runId(), attempt.nodeId(), attempt.attempt(), attempt.status().name(),
                attempt.inputJson(), attempt.outputJson(), attempt.errorCode(), attempt.errorMessage(),
                timestamp(attempt.startedAt()), nullableTimestamp(attempt.completedAt()), attempt.nodeId(),
                attempt.tenantId(), attempt.runId());
        if (inserted != 1) throw new IllegalStateException("Pipeline run cannot accept node attempts");
        return attempt;
    }

    @Override
    public boolean completeNodeAttempt(
            String tenantId, String runId, String nodeId, int attempt,
            NodeAttemptStatus expected, NodeAttemptStatus terminal,
            Instant completedAt, String outputJson, String errorCode, String errorMessage) {
        if (!terminal.terminal()) throw new IllegalArgumentException("terminal status is required");
        PipelinePersistenceSupport.instant(completedAt, "completedAt");
        String runGuard = terminal == NodeAttemptStatus.CANCELLED ? "" : """
                 AND EXISTS (SELECT 1 FROM pipeline_runs r
                     WHERE r.tenant_id = pipeline_node_attempts.tenant_id
                       AND r.run_id = pipeline_node_attempts.run_id
                       AND r.status = 'RUNNING' AND r.cancel_requested = FALSE)
                """;
        return jdbc.update("""
                UPDATE pipeline_node_attempts
                SET status = ?, output_json = ?, error_code = ?, error_message = ?, completed_at = ?
                WHERE tenant_id = ? AND run_id = ? AND node_id = ? AND attempt_no = ? AND status = ?
                """ + runGuard,
                terminal.name(), outputJson, errorCode, errorMessage, timestamp(completedAt),
                tenant(tenantId), id(runId, "runId", 64), id(nodeId, "nodeId", 96), attempt,
                expected.name()) == 1;
    }

    @Override
    public List<PipelineNodeAttempt> findNodeAttempts(String tenantId, String runId) {
        return jdbc.query("""
                SELECT * FROM pipeline_node_attempts
                WHERE tenant_id = ? AND run_id = ? ORDER BY started_at, node_id, attempt_no
                """, this::mapAttempt, tenant(tenantId), id(runId, "runId", 64));
    }

    @Override
    public PipelineRunEvent appendEvent(PipelineRunEvent requested) {
        return transaction.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE pipeline_run_event_sequences SET next_sequence = next_sequence + 1
                    WHERE tenant_id = ? AND run_id = ?
                    """, requested.tenantId(), requested.runId());
            if (updated != 1) throw new IllegalStateException("Pipeline run does not exist");
            Long sequence = jdbc.queryForObject("""
                    SELECT next_sequence - 1 FROM pipeline_run_event_sequences
                    WHERE tenant_id = ? AND run_id = ?
                    """, Long.class, requested.tenantId(), requested.runId());
            if (sequence == null) throw new IllegalStateException("Unable to allocate pipeline event sequence");
            PipelineRunEvent event = new PipelineRunEvent(
                    requested.tenantId(), requested.runId(), sequence, requested.eventType(), requested.nodeId(),
                    requested.attempt(), requested.payloadJson(), requested.occurredAt());
            jdbc.update("""
                    INSERT INTO pipeline_run_events
                    (tenant_id, run_id, event_sequence, event_type, node_id, attempt_no, payload_json, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, event.tenantId(), event.runId(), event.sequence(), event.eventType(), event.nodeId(),
                    event.attempt(), event.payloadJson(), timestamp(event.occurredAt()));
            return event;
        });
    }

    @Override
    public List<PipelineRunEvent> findEvents(String tenantId, String runId, long afterSequence, int limit) {
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must not be negative");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return jdbc.query("""
                SELECT * FROM pipeline_run_events
                WHERE tenant_id = ? AND run_id = ? AND event_sequence > ?
                ORDER BY event_sequence LIMIT ?
                """, this::mapEvent, tenant(tenantId), id(runId, "runId", 64), afterSequence, limit);
    }

    private void insertDefinitionVersion(PipelineDefinition definition) {
        jdbc.update("""
                INSERT INTO pipeline_definition_versions
                (tenant_id, pipeline_id, version, name, description, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, definition.tenantId(), definition.id(), definition.version(), definition.name(),
                definition.description(), definition.createdBy(), timestamp(definition.updatedAt()));
        for (PipelineNodeDefinition node : definition.nodes()) {
            jdbc.update("""
                    INSERT INTO pipeline_nodes
                    (tenant_id, pipeline_id, pipeline_version, node_id, capability_id,
                     config_json, timeout_ms, max_attempts)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, definition.tenantId(), definition.id(), definition.version(), node.nodeId(),
                    node.capabilityId(), node.configJson(), node.timeoutMillis(), node.maxAttempts());
        }
        for (PipelineEdgeDefinition edge : definition.edges()) {
            jdbc.update("""
                    INSERT INTO pipeline_edges
                    (tenant_id, pipeline_id, pipeline_version, source_node_id, target_node_id,
                     source_output, target_input)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, definition.tenantId(), definition.id(), definition.version(), edge.sourceNodeId(),
                    edge.targetNodeId(), databaseEdgeValue(edge.sourceOutput()), databaseEdgeValue(edge.targetInput()));
        }
    }

    private PipelineDefinition loadDefinition(DefinitionMetadata metadata) {
        List<PipelineNodeDefinition> nodes = jdbc.query("""
                SELECT node_id, capability_id, config_json, timeout_ms, max_attempts
                FROM pipeline_nodes
                WHERE tenant_id = ? AND pipeline_id = ? AND pipeline_version = ? ORDER BY node_id
                """, (rs, rowNum) -> new PipelineNodeDefinition(rs.getString("node_id"),
                rs.getString("capability_id"), rs.getString("config_json"), rs.getLong("timeout_ms"),
                rs.getInt("max_attempts")), metadata.tenantId(), metadata.id(), metadata.version());
        List<PipelineEdgeDefinition> edges = jdbc.query("""
                SELECT source_node_id, target_node_id, source_output, target_input
                FROM pipeline_edges
                WHERE tenant_id = ? AND pipeline_id = ? AND pipeline_version = ?
                ORDER BY source_node_id, target_node_id, source_output, target_input
                """, (rs, rowNum) -> new PipelineEdgeDefinition(rs.getString("source_node_id"),
                rs.getString("target_node_id"), nullableEdgeValue(rs.getString("source_output")),
                nullableEdgeValue(rs.getString("target_input"))),
                metadata.tenantId(), metadata.id(), metadata.version());
        return new PipelineDefinition(metadata.tenantId(), metadata.id(), metadata.name(), metadata.description(),
                metadata.version(), nodes, edges, metadata.createdBy(), metadata.createdAt(), metadata.updatedAt());
    }

    private DefinitionMetadata mapDefinitionMetadata(ResultSet rs, int rowNum) throws SQLException {
        return new DefinitionMetadata(rs.getString("tenant_id"), rs.getString("id"), rs.getString("name"),
                rs.getString("description"), rs.getLong("version"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private PipelineRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new PipelineRun(rs.getString("tenant_id"), rs.getString("run_id"), rs.getString("pipeline_id"),
                rs.getLong("pipeline_version"), PipelineRunStatus.valueOf(rs.getString("status")),
                rs.getString("input_json"), rs.getString("output_json"), rs.getString("error_code"),
                rs.getString("error_message"), rs.getBoolean("cancel_requested"), rs.getLong("revision"),
                rs.getString("actor_id"), instant(rs, "created_at"), nullableInstant(rs, "started_at"),
                instant(rs, "updated_at"), nullableInstant(rs, "completed_at"));
    }

    private PipelineNodeAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new PipelineNodeAttempt(rs.getString("tenant_id"), rs.getString("run_id"),
                rs.getString("node_id"), rs.getInt("attempt_no"),
                NodeAttemptStatus.valueOf(rs.getString("status")), rs.getString("input_json"),
                rs.getString("output_json"), rs.getString("error_code"), rs.getString("error_message"),
                instant(rs, "started_at"), nullableInstant(rs, "completed_at"));
    }

    private PipelineRunEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new PipelineRunEvent(rs.getString("tenant_id"), rs.getString("run_id"),
                rs.getLong("event_sequence"), rs.getString("event_type"), rs.getString("node_id"),
                rs.getObject("attempt_no", Integer.class), rs.getString("payload_json"),
                instant(rs, "occurred_at"));
    }

    private void ensureTenant(String tenantId, Instant now) {
        try {
            jdbc.update("INSERT INTO tenants (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    tenantId, tenantId, timestamp(now), timestamp(now));
        } catch (DuplicateKeyException ignored) {
            // Tenant already exists.
        }
    }

    private static String tenant(String value) {
        return PipelinePersistenceSupport.text(value, "tenantId", 64);
    }

    private static String id(String value, String field, int maximumLength) {
        return PipelinePersistenceSupport.text(value, field, maximumLength);
    }

    private static String databaseEdgeValue(String value) {
        return value == null ? "" : value;
    }

    private static String nullableEdgeValue(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(PipelinePersistenceSupport.instant(value, "instant"));
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

    private record DefinitionMetadata(
            String tenantId, String id, String name, String description, long version,
            String createdBy, Instant createdAt, Instant updatedAt) {
    }
}
