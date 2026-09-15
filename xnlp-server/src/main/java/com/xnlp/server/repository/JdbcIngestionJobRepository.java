package com.xnlp.server.repository;

import com.xnlp.core.rag.IngestionJob;
import com.xnlp.core.repository.IngestionJobRepository;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Portable JDBC adapter for durable ingestion progress and cancellation. */
@Repository
@Profile("!memory")
public class JdbcIngestionJobRepository implements IngestionJobRepository {

    private final JdbcTemplate jdbc;

    public JdbcIngestionJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IngestionJob> findByKnowledgeBase(String tenantId, String knowledgeBaseId) {
        return jdbc.query("""
                SELECT id, knowledge_base_id, status, total_documents, processed_documents,
                    failed_documents, error_summary, created_at, completed_at
                FROM ingestion_jobs
                WHERE tenant_id = ? AND knowledge_base_id = ?
                ORDER BY created_at DESC, id
                """, this::mapRow, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"));
    }

    @Override
    public Optional<IngestionJob> findById(String tenantId, String id) {
        return jdbc.query("""
                SELECT id, knowledge_base_id, status, total_documents, processed_documents,
                    failed_documents, error_summary, created_at, completed_at
                FROM ingestion_jobs
                WHERE tenant_id = ? AND id = ?
                """, this::mapRow, tenant(tenantId), requireText(id, "id")).stream().findFirst();
    }

    @Override
    public IngestionJob save(String tenantId, IngestionJob job) {
        String tenant = tenant(tenantId);
        Instant createdAt = job.createdAt() == null ? Instant.now() : job.createdAt();
        IngestionJob persisted = new IngestionJob(
                job.id(), job.knowledgeBaseId(), job.status(), job.totalDocuments(),
                job.processedDocuments(), job.failedDocuments(), job.errorSummary(), createdAt, job.completedAt());
        Object[] updateArguments = updateArguments(tenant, persisted);
        int updated = jdbc.update("""
                UPDATE ingestion_jobs SET knowledge_base_id = ?, status = ?, total_documents = ?,
                    processed_documents = ?, failed_documents = ?, error_summary = ?, completed_at = ?
                WHERE tenant_id = ? AND id = ?
                """, updateArguments);
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO ingestion_jobs
                            (tenant_id, id, knowledge_base_id, status, total_documents, processed_documents,
                             failed_documents, error_summary, created_at, completed_at, cancel_requested)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                        """, tenant, persisted.id(), persisted.knowledgeBaseId(), persisted.status().name(),
                        persisted.totalDocuments(), persisted.processedDocuments(), persisted.failedDocuments(),
                        persisted.errorSummary(), Timestamp.from(persisted.createdAt()), timestamp(persisted.completedAt()));
            } catch (DuplicateKeyException race) {
                jdbc.update("""
                        UPDATE ingestion_jobs SET knowledge_base_id = ?, status = ?, total_documents = ?,
                            processed_documents = ?, failed_documents = ?, error_summary = ?, completed_at = ?
                        WHERE tenant_id = ? AND id = ?
                        """, updateArguments);
            }
        }
        return persisted;
    }

    @Override
    public boolean requestCancellation(String tenantId, String id) {
        return jdbc.update("""
                UPDATE ingestion_jobs SET cancel_requested = TRUE
                WHERE tenant_id = ? AND id = ?
                    AND status IN ('PENDING', 'RUNNING')
                """, tenant(tenantId), requireText(id, "id")) > 0;
    }

    @Override
    public boolean isCancellationRequested(String tenantId, String id) {
        Boolean requested = jdbc.query("""
                SELECT cancel_requested FROM ingestion_jobs
                WHERE tenant_id = ? AND id = ?
                """, resultSet -> resultSet.next() ? resultSet.getBoolean(1) : null,
                tenant(tenantId), requireText(id, "id"));
        return Boolean.TRUE.equals(requested);
    }

    @Override
    public void deleteByKnowledgeBase(String tenantId, String knowledgeBaseId) {
        jdbc.update("""
                DELETE FROM ingestion_jobs
                WHERE tenant_id = ? AND knowledge_base_id = ?
                """, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"));
    }

    private Object[] updateArguments(String tenantId, IngestionJob job) {
        return new Object[]{
                job.knowledgeBaseId(), job.status().name(), job.totalDocuments(), job.processedDocuments(),
                job.failedDocuments(), job.errorSummary(), timestamp(job.completedAt()), tenantId, job.id()
        };
    }

    private IngestionJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IngestionJob(
                rs.getString("id"), rs.getString("knowledge_base_id"),
                IngestionJob.Status.valueOf(rs.getString("status")), rs.getInt("total_documents"),
                rs.getInt("processed_documents"), rs.getInt("failed_documents"), rs.getString("error_summary"),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("completed_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String tenant(String tenantId) {
        return TenantContext.normalize(requireText(tenantId, "tenantId"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
