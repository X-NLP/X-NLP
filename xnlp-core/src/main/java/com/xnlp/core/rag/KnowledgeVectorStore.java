package com.xnlp.core.rag;

import java.util.List;

/**
 * Project-level vector persistence SPI.
 *
 * <p>Implementations may use portable JDBC, pgvector or a remote vector
 * database without changing ingestion and retrieval services.</p>
 */
public interface KnowledgeVectorStore {

    void upsert(String tenantId, List<VectorRecord> records);

    List<RetrievalMatch> search(VectorSearchRequest request);

    void deleteByDocument(String tenantId, String knowledgeBaseId, String documentId);

    void deleteByKnowledgeBase(String tenantId, String knowledgeBaseId);

    long count(String tenantId, String knowledgeBaseId);
}
