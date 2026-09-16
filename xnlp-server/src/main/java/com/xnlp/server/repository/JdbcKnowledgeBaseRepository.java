package com.xnlp.server.repository;

import com.xnlp.core.rag.ChunkPolicy;
import com.xnlp.core.rag.KnowledgeBase;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.repository.KnowledgeBaseRepository;
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
import java.util.Optional;

/** Portable JDBC adapter for knowledge-base descriptors. */
@Repository
@Profile("!memory")
public class JdbcKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final JdbcTemplate jdbc;

    public JdbcKnowledgeBaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<KnowledgeBase> findAll(String tenantId) {
        return jdbc.query("""
                SELECT id, name, description, embedding_model, chunk_max_characters,
                    chunk_overlap_characters, chunk_separator_mode, status,
                    document_count, chunk_count, created_at, updated_at
                FROM knowledge_bases WHERE tenant_id = ? ORDER BY created_at DESC, id
                """, this::mapRow, tenant(tenantId));
    }

    @Override
    public Optional<KnowledgeBase> findById(String tenantId, String id) {
        return jdbc.query("""
                SELECT id, name, description, embedding_model, chunk_max_characters,
                    chunk_overlap_characters, chunk_separator_mode, status,
                    document_count, chunk_count, created_at, updated_at
                FROM knowledge_bases WHERE tenant_id = ? AND id = ?
                """, this::mapRow, tenant(tenantId), requireText(id, "id")).stream().findFirst();
    }

    @Override
    public KnowledgeBase save(String tenantId, KnowledgeBase knowledgeBase) {
        String tenant = tenant(tenantId);
        Instant now = knowledgeBase.updatedAt() == null ? Instant.now() : knowledgeBase.updatedAt();
        Instant createdAt = knowledgeBase.createdAt() == null ? now : knowledgeBase.createdAt();
        KnowledgeBase persisted = new KnowledgeBase(
                knowledgeBase.id(), knowledgeBase.name(), knowledgeBase.description(),
                knowledgeBase.embeddingModel(), knowledgeBase.chunkPolicy(), knowledgeBase.status(),
                knowledgeBase.documentCount(), knowledgeBase.chunkCount(), createdAt, now);
        Object[] updateArguments = updateArguments(tenant, persisted);
        int updated = jdbc.update("""
                UPDATE knowledge_bases SET name = ?, description = ?, embedding_model = ?,
                    chunk_max_characters = ?, chunk_overlap_characters = ?, chunk_separator_mode = ?,
                    status = ?, document_count = ?, chunk_count = ?, updated_at = ?
                WHERE tenant_id = ? AND id = ?
                """, updateArguments);
        if (updated == 0) {
            try {
                insert(tenant, persisted);
            } catch (DuplicateKeyException raceOrConflict) {
                if (jdbc.update("""
                        UPDATE knowledge_bases SET name = ?, description = ?, embedding_model = ?,
                            chunk_max_characters = ?, chunk_overlap_characters = ?, chunk_separator_mode = ?,
                            status = ?, document_count = ?, chunk_count = ?, updated_at = ?
                        WHERE tenant_id = ? AND id = ?
                        """, updateArguments) == 0) {
                    throw new RagContractException(
                            RagErrorCode.KNOWLEDGE_BASE_CONFLICT,
                            "A knowledge base with the same name already exists",
                            Map.of("name", persisted.name()));
                }
            }
        }
        return persisted;
    }

    @Override
    public boolean deleteById(String tenantId, String id) {
        return jdbc.update("DELETE FROM knowledge_bases WHERE tenant_id = ? AND id = ?",
                tenant(tenantId), requireText(id, "id")) > 0;
    }

    private void insert(String tenantId, KnowledgeBase knowledgeBase) {
        jdbc.update("""
                INSERT INTO knowledge_bases
                    (tenant_id, id, name, description, embedding_model, chunk_max_characters,
                     chunk_overlap_characters, chunk_separator_mode, status, document_count,
                     chunk_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, knowledgeBase.id(), knowledgeBase.name(), knowledgeBase.description(),
                knowledgeBase.embeddingModel(), knowledgeBase.chunkPolicy().maxCharacters(),
                knowledgeBase.chunkPolicy().overlapCharacters(), knowledgeBase.chunkPolicy().separatorMode().name(),
                knowledgeBase.status().name(), knowledgeBase.documentCount(), knowledgeBase.chunkCount(),
                Timestamp.from(knowledgeBase.createdAt()), Timestamp.from(knowledgeBase.updatedAt()));
    }

    private Object[] updateArguments(String tenantId, KnowledgeBase knowledgeBase) {
        return new Object[]{
                knowledgeBase.name(), knowledgeBase.description(), knowledgeBase.embeddingModel(),
                knowledgeBase.chunkPolicy().maxCharacters(), knowledgeBase.chunkPolicy().overlapCharacters(),
                knowledgeBase.chunkPolicy().separatorMode().name(), knowledgeBase.status().name(),
                knowledgeBase.documentCount(), knowledgeBase.chunkCount(), Timestamp.from(knowledgeBase.updatedAt()),
                tenantId, knowledgeBase.id()
        };
    }

    private KnowledgeBase mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeBase(
                rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("embedding_model"),
                new ChunkPolicy(rs.getInt("chunk_max_characters"), rs.getInt("chunk_overlap_characters"),
                        ChunkPolicy.SeparatorMode.valueOf(rs.getString("chunk_separator_mode"))),
                KnowledgeBase.Status.valueOf(rs.getString("status")),
                rs.getLong("document_count"), rs.getLong("chunk_count"),
                toInstant(rs.getTimestamp("created_at")), toInstant(rs.getTimestamp("updated_at")));
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
