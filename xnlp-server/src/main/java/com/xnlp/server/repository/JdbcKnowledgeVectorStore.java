package com.xnlp.server.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.rag.KnowledgeVectorStore;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.rag.VectorRecord;
import com.xnlp.core.rag.VectorSearchRequest;
import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portable JDBC vector store for development and bounded knowledge bases.
 *
 * <p>Embeddings are persisted as JSON so the same repository code works with
 * H2, MySQL and PostgreSQL. Cosine similarity and metadata filtering are
 * intentionally performed in the application after loading at most
 * {@value #MAX_CANDIDATES} candidates.</p>
 */
@Repository
@Primary
@Profile("!memory")
public class JdbcKnowledgeVectorStore implements KnowledgeVectorStore {

    static final int MAX_CANDIDATES = 10_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    public JdbcKnowledgeVectorStore(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public void upsert(String tenantId, List<VectorRecord> records) {
        String tenant = TenantContext.normalize(requireText(tenantId, "tenantId"));
        List<VectorRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        for (VectorRecord record : safeRecords) {
            upsertOne(tenant, record);
        }
    }

    private void upsertOne(String tenantId, VectorRecord record) {
        Instant updatedAt = record.updatedAt() == null ? Instant.now() : record.updatedAt();
        Object[] updateArguments = {
                record.knowledgeBaseId(), record.documentId(), record.dimensions(), toJson(record.embedding()),
                record.contentChecksum(), toJson(record.metadata()), Timestamp.from(updatedAt), tenantId,
                record.chunkId(), record.embeddingModel()
        };
        int updated = jdbc.update("""
                UPDATE knowledge_embeddings
                SET knowledge_base_id = ?, document_id = ?, dimensions = ?, embedding_json = ?,
                    content_checksum = ?, metadata_json = ?, updated_at = ?
                WHERE tenant_id = ? AND chunk_id = ? AND embedding_model = ?
                """, updateArguments);
        if (updated > 0) {
            return;
        }

        try {
            jdbc.update("""
                    INSERT INTO knowledge_embeddings
                        (tenant_id, chunk_id, knowledge_base_id, document_id, embedding_model, dimensions,
                         embedding_json, content_checksum, metadata_json, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, record.chunkId(), record.knowledgeBaseId(), record.documentId(),
                    record.embeddingModel(), record.dimensions(), toJson(record.embedding()),
                    record.contentChecksum(), toJson(record.metadata()), Timestamp.from(updatedAt));
        } catch (DuplicateKeyException race) {
            jdbc.update("""
                    UPDATE knowledge_embeddings
                    SET knowledge_base_id = ?, document_id = ?, dimensions = ?, embedding_json = ?,
                        content_checksum = ?, metadata_json = ?, updated_at = ?
                    WHERE tenant_id = ? AND chunk_id = ? AND embedding_model = ?
                    """, updateArguments);
        }
    }

    @Override
    public List<RetrievalMatch> search(VectorSearchRequest request) {
        String tenantId = TenantContext.normalize(requireText(request.tenantId(), "tenantId"));
        List<Candidate> candidates = jdbc.query("""
                SELECT e.chunk_id, e.document_id, e.dimensions, e.embedding_json,
                       e.metadata_json AS embedding_metadata,
                       c.content_text, c.metadata_json AS chunk_metadata,
                       d.title, d.source_uri, d.metadata_json AS document_metadata
                FROM knowledge_embeddings e
                JOIN knowledge_chunks c
                  ON c.tenant_id = e.tenant_id AND c.id = e.chunk_id
                 AND c.knowledge_base_id = e.knowledge_base_id AND c.document_id = e.document_id
                JOIN knowledge_documents d
                  ON d.tenant_id = e.tenant_id AND d.id = e.document_id
                 AND d.knowledge_base_id = e.knowledge_base_id
                WHERE e.tenant_id = ? AND e.knowledge_base_id = ? AND e.embedding_model = ?
                ORDER BY e.chunk_id
                LIMIT ?
                """, this::mapCandidate, tenantId, request.knowledgeBaseId(),
                request.embeddingModel(), MAX_CANDIDATES + 1);

        if (candidates.size() > MAX_CANDIDATES) {
            throw new RagContractException(
                    RagErrorCode.VECTOR_STORE_CAPACITY_EXCEEDED,
                    "Portable JDBC vector search exceeds the configured candidate capacity",
                    Map.of("maxCandidates", MAX_CANDIDATES, "knowledgeBaseId", request.knowledgeBaseId()));
        }

        float[] queryVector = request.queryVector();
        List<ScoredCandidate> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            validateStoredEmbedding(candidate);
            if (candidate.dimensions() != queryVector.length) {
                throw new RagContractException(
                        RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                        "Query vector dimensions do not match stored embeddings",
                        Map.of("expected", candidate.dimensions(), "actual", queryVector.length,
                                "chunkId", candidate.chunkId()));
            }
            if (!matchesFilter(candidate.metadata(), request.filter())) {
                continue;
            }
            double score = cosine(queryVector, candidate.embedding());
            if (request.minScore() == null || score >= request.minScore()) {
                scored.add(new ScoredCandidate(candidate, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(item -> item.candidate().documentId())
                        .thenComparing(item -> item.candidate().chunkId()))
                .limit(request.topK())
                .map(item -> new RetrievalMatch(
                        item.candidate().documentId(), item.candidate().chunkId(), item.candidate().title(),
                        item.candidate().content(), item.candidate().sourceUri(), item.score(), null,
                        item.candidate().metadata()))
                .toList();
    }

    @Override
    public void deleteByDocument(String tenantId, String knowledgeBaseId, String documentId) {
        jdbc.update("""
                DELETE FROM knowledge_embeddings
                WHERE tenant_id = ? AND knowledge_base_id = ? AND document_id = ?
                """, TenantContext.normalize(requireText(tenantId, "tenantId")),
                requireText(knowledgeBaseId, "knowledgeBaseId"),
                requireText(documentId, "documentId"));
    }

    @Override
    public void deleteByKnowledgeBase(String tenantId, String knowledgeBaseId) {
        jdbc.update("DELETE FROM knowledge_embeddings WHERE tenant_id = ? AND knowledge_base_id = ?",
                TenantContext.normalize(requireText(tenantId, "tenantId")),
                requireText(knowledgeBaseId, "knowledgeBaseId"));
    }

    @Override
    public long count(String tenantId, String knowledgeBaseId) {
        Long result = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_embeddings
                WHERE tenant_id = ? AND knowledge_base_id = ?
                """, Long.class, TenantContext.normalize(requireText(tenantId, "tenantId")),
                requireText(knowledgeBaseId, "knowledgeBaseId"));
        return result == null ? 0 : result;
    }

    private void validateStoredEmbedding(Candidate candidate) {
        float[] embedding = candidate.embedding();
        int actualDimensions = embedding == null ? 0 : embedding.length;
        if (actualDimensions != candidate.dimensions()) {
            throw new RagContractException(
                    RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                    "Stored embedding dimensions do not match the declared dimensions",
                    Map.of("declared", candidate.dimensions(), "actual", actualDimensions,
                            "chunkId", candidate.chunkId()));
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new RagContractException(
                        RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                        "Stored embedding contains non-finite values",
                        Map.of("chunkId", candidate.chunkId()));
            }
        }
    }

    private Candidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(fromJsonMap(rs.getString("document_metadata")));
        metadata.putAll(fromJsonMap(rs.getString("chunk_metadata")));
        metadata.putAll(fromJsonMap(rs.getString("embedding_metadata")));
        return new Candidate(
                rs.getString("chunk_id"),
                rs.getString("document_id"),
                rs.getString("title"),
                rs.getString("content_text"),
                rs.getString("source_uri"),
                rs.getInt("dimensions"),
                fromJsonVector(rs.getString("embedding_json")),
                metadata);
    }

    private boolean matchesFilter(Map<String, Object> metadata, Map<String, Object> filter) {
        return filter.entrySet().stream().allMatch(entry -> metadata.containsKey(entry.getKey())
                && java.util.Objects.equals(metadata.get(entry.getKey()), entry.getValue()));
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Vector data cannot be serialized", ex);
        }
    }

    private float[] fromJsonVector(String value) throws SQLException {
        try {
            return jsonMapper.readValue(value, float[].class);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid embedding JSON", ex);
        }
    }

    private Map<String, Object> fromJsonMap(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid vector metadata JSON", ex);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private record Candidate(
            String chunkId,
            String documentId,
            String title,
            String content,
            String sourceUri,
            int dimensions,
            float[] embedding,
            Map<String, Object> metadata) {
    }

    private record ScoredCandidate(Candidate candidate, double score) {
    }
}
