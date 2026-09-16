package com.xnlp.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.rag.KnowledgeDocument;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.repository.KnowledgeDocumentRepository;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Portable JDBC adapter for source documents and indexing state. */
@Repository
@Profile("!memory")
public class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private static final String FIND_BY_KNOWLEDGE_BASE_SQL = """
            SELECT id, knowledge_base_id, external_id, title, source_type, source_uri,
                   content_text, content_checksum, version, index_status, error_message,
                   metadata_json, created_at, updated_at
            FROM knowledge_documents
            WHERE tenant_id = ? AND knowledge_base_id = ?
            ORDER BY updated_at DESC, id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, knowledge_base_id, external_id, title, source_type, source_uri,
                   content_text, content_checksum, version, index_status, error_message,
                   metadata_json, created_at, updated_at
            FROM knowledge_documents
            WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ?
            """;

    private static final String FIND_BY_EXTERNAL_ID_SQL = """
            SELECT id, knowledge_base_id, external_id, title, source_type, source_uri,
                   content_text, content_checksum, version, index_status, error_message,
                   metadata_json, created_at, updated_at
            FROM knowledge_documents
            WHERE tenant_id = ? AND knowledge_base_id = ? AND external_id = ?
            """;

    private static final String UPDATE_SQL = """
            UPDATE knowledge_documents SET external_id = ?, title = ?, source_type = ?, source_uri = ?,
                content_text = ?, content_checksum = ?, version = ?, index_status = ?, error_message = ?,
                metadata_json = ?, updated_at = ?
            WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ?
            """;

    private static final String UPDATE_WITH_VERSION_SQL = """
            UPDATE knowledge_documents SET external_id = ?, title = ?, source_type = ?, source_uri = ?,
                content_text = ?, content_checksum = ?, version = ?, index_status = ?, error_message = ?,
                metadata_json = ?, updated_at = ?
            WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ? AND version = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    public JdbcKnowledgeDocumentRepository(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<KnowledgeDocument> findByKnowledgeBase(String tenantId, String knowledgeBaseId) {
        return jdbc.query(FIND_BY_KNOWLEDGE_BASE_SQL, this::mapRow,
                tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"));
    }

    @Override
    public Optional<KnowledgeDocument> findById(String tenantId, String knowledgeBaseId, String id) {
        return jdbc.query(FIND_BY_ID_SQL, this::mapRow,
                tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"), requireText(id, "id"))
                .stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocument> findByExternalId(String tenantId, String knowledgeBaseId, String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query(FIND_BY_EXTERNAL_ID_SQL, this::mapRow,
                tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"), externalId.strip())
                .stream().findFirst();
    }

    @Override
    public KnowledgeDocument save(String tenantId, KnowledgeDocument document) {
        String tenant = tenant(tenantId);
        Instant now = document.updatedAt() == null ? Instant.now() : document.updatedAt();
        Instant createdAt = document.createdAt() == null ? now : document.createdAt();
        KnowledgeDocument persisted = new KnowledgeDocument(
                document.id(), document.knowledgeBaseId(), normalizeOptional(document.externalId()),
                document.title(), document.sourceType(), normalizeOptional(document.sourceUri()),
                document.content(), document.contentChecksum(), document.version(), document.indexStatus(),
                document.errorMessage(), document.metadata(), createdAt, now);
        Object[] updateArguments = updateArguments(tenant, persisted);
        int updated = jdbc.update(UPDATE_SQL, updateArguments);
        if (updated == 0) {
            try {
                insert(tenant, persisted);
            } catch (DuplicateKeyException raceOrConflict) {
                if (jdbc.update(UPDATE_SQL, updateArguments) == 0) {
                    throw new RagContractException(
                            RagErrorCode.DOCUMENT_CONFLICT,
                            "A document with the same externalId already exists",
                            Map.of("externalId", String.valueOf(persisted.externalId())));
                }
            }
        }
        return persisted;
    }

    @Override
    public boolean update(String tenantId, KnowledgeDocument document, long expectedVersion) {
        String tenant = tenant(tenantId);
        Instant now = document.updatedAt() == null ? Instant.now() : document.updatedAt();
        Instant createdAt = document.createdAt() == null ? now : document.createdAt();
        KnowledgeDocument persisted = new KnowledgeDocument(
                document.id(), document.knowledgeBaseId(), normalizeOptional(document.externalId()),
                document.title(), document.sourceType(), normalizeOptional(document.sourceUri()),
                document.content(), document.contentChecksum(), document.version(), document.indexStatus(),
                document.errorMessage(), document.metadata(), createdAt, now);
        Object[] baseArguments = updateArguments(tenant, persisted);
        Object[] versionedArguments = java.util.Arrays.copyOf(baseArguments, baseArguments.length + 1);
        versionedArguments[baseArguments.length] = expectedVersion;
        return jdbc.update(UPDATE_WITH_VERSION_SQL, versionedArguments) > 0;
    }

    @Override
    public long countByKnowledgeBase(String tenantId, String knowledgeBaseId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_documents
                WHERE tenant_id = ? AND knowledge_base_id = ?
                """, Long.class, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"));
        return count == null ? 0 : count;
    }

    @Override
    public boolean updateIndexStatus(
            String tenantId,
            String knowledgeBaseId,
            String id,
            long expectedVersion,
            KnowledgeDocument.IndexStatus status,
            String errorMessage,
            Instant updatedAt) {
        return jdbc.update("""
                UPDATE knowledge_documents
                SET index_status = ?, error_message = ?, updated_at = ?
                WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ? AND version = ?
                """, Objects.requireNonNull(status, "status must not be null").name(), errorMessage,
                Timestamp.from(updatedAt == null ? Instant.now() : updatedAt), tenant(tenantId),
                requireText(knowledgeBaseId, "knowledgeBaseId"), requireText(id, "id"), expectedVersion) > 0;
    }

    @Override
    public boolean deleteById(String tenantId, String knowledgeBaseId, String id) {
        return jdbc.update("""
                DELETE FROM knowledge_documents
                WHERE tenant_id = ? AND knowledge_base_id = ? AND id = ?
                """, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"), requireText(id, "id")) > 0;
    }

    private void insert(String tenantId, KnowledgeDocument document) {
        jdbc.update("""
                INSERT INTO knowledge_documents
                    (tenant_id, id, knowledge_base_id, external_id, title, source_type, source_uri,
                     content_text, content_checksum, version, index_status, error_message, metadata_json,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, document.id(), document.knowledgeBaseId(), document.externalId(), document.title(),
                document.sourceType().name(), document.sourceUri(), document.content(), document.contentChecksum(),
                document.version(), document.indexStatus().name(), document.errorMessage(), toJson(document.metadata()),
                Timestamp.from(document.createdAt()), Timestamp.from(document.updatedAt()));
    }

    private Object[] updateArguments(String tenantId, KnowledgeDocument document) {
        return new Object[]{
                document.externalId(), document.title(), document.sourceType().name(), document.sourceUri(),
                document.content(), document.contentChecksum(), document.version(), document.indexStatus().name(),
                document.errorMessage(), toJson(document.metadata()), Timestamp.from(document.updatedAt()), tenantId,
                document.knowledgeBaseId(), document.id()
        };
    }

    private KnowledgeDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("id"), rs.getString("knowledge_base_id"), rs.getString("external_id"),
                rs.getString("title"), KnowledgeDocument.SourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_uri"), rs.getString("content_text"), rs.getString("content_checksum"),
                rs.getLong("version"), KnowledgeDocument.IndexStatus.valueOf(rs.getString("index_status")),
                rs.getString("error_message"), fromJson(rs.getString("metadata_json")),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("updated_at")));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return jsonMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Document metadata cannot be serialized", ex);
        }
    }

    private Map<String, Object> fromJson(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid document metadata JSON", ex);
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
