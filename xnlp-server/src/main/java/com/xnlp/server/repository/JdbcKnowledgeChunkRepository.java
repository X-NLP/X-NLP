package com.xnlp.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.rag.KnowledgeChunk;
import com.xnlp.core.repository.KnowledgeChunkRepository;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Portable JDBC adapter for deterministic document chunks. */
@Repository
@Profile("!memory")
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<KnowledgeChunk> findByDocument(String tenantId, String knowledgeBaseId, String documentId) {
        return jdbc.query("""
                SELECT id, knowledge_base_id, document_id, seq, content_text, content_checksum,
                    start_offset, end_offset, metadata_json
                FROM knowledge_chunks
                WHERE tenant_id = ? AND knowledge_base_id = ? AND document_id = ?
                ORDER BY seq, id
                """, this::mapRow, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"),
                requireText(documentId, "documentId"));
    }

    @Override
    @Transactional
    public void replaceByDocument(
            String tenantId,
            String knowledgeBaseId,
            String documentId,
            List<KnowledgeChunk> chunks) {
        String tenant = tenant(tenantId);
        String knowledgeBase = requireText(knowledgeBaseId, "knowledgeBaseId");
        String document = requireText(documentId, "documentId");
        List<KnowledgeChunk> safeChunks = chunks == null ? List.of() : List.copyOf(chunks);
        for (KnowledgeChunk chunk : safeChunks) {
            if (!knowledgeBase.equals(chunk.knowledgeBaseId()) || !document.equals(chunk.documentId())) {
                throw new IllegalArgumentException("chunk does not belong to the requested document");
            }
        }

        jdbc.update("""
                DELETE FROM knowledge_chunks
                WHERE tenant_id = ? AND knowledge_base_id = ? AND document_id = ?
                """, tenant, knowledgeBase, document);
        if (safeChunks.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO knowledge_chunks
                    (tenant_id, id, knowledge_base_id, document_id, seq, content_text,
                     content_checksum, start_offset, end_offset, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        KnowledgeChunk chunk = safeChunks.get(index);
                        statement.setString(1, tenant);
                        statement.setString(2, chunk.id());
                        statement.setString(3, chunk.knowledgeBaseId());
                        statement.setString(4, chunk.documentId());
                        statement.setInt(5, chunk.sequence());
                        statement.setString(6, chunk.content());
                        statement.setString(7, chunk.contentChecksum());
                        statement.setInt(8, chunk.startOffset());
                        statement.setInt(9, chunk.endOffset());
                        statement.setString(10, toJson(chunk.metadata()));
                    }

                    @Override
                    public int getBatchSize() {
                        return safeChunks.size();
                    }
                });
    }

    @Override
    public void deleteByDocument(String tenantId, String knowledgeBaseId, String documentId) {
        jdbc.update("""
                DELETE FROM knowledge_chunks
                WHERE tenant_id = ? AND knowledge_base_id = ? AND document_id = ?
                """, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"),
                requireText(documentId, "documentId"));
    }

    @Override
    public long countByKnowledgeBase(String tenantId, String knowledgeBaseId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunks
                WHERE tenant_id = ? AND knowledge_base_id = ?
                """, Long.class, tenant(tenantId), requireText(knowledgeBaseId, "knowledgeBaseId"));
        return count == null ? 0 : count;
    }

    private KnowledgeChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeChunk(
                rs.getString("id"), rs.getString("knowledge_base_id"), rs.getString("document_id"),
                rs.getInt("seq"), rs.getString("content_text"), rs.getString("content_checksum"),
                rs.getInt("start_offset"), rs.getInt("end_offset"), fromJson(rs.getString("metadata_json")));
    }

    private String toJson(Map<String, Object> value) throws SQLException {
        try {
            return jsonMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Chunk metadata cannot be serialized", ex);
        }
    }

    private Map<String, Object> fromJson(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid chunk metadata JSON", ex);
        }
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
