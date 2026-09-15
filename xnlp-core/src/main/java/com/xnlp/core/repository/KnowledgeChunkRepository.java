package com.xnlp.core.repository;

import com.xnlp.core.rag.KnowledgeChunk;

import java.util.List;

/** Tenant-explicit persistence contract for deterministic document chunks. */
public interface KnowledgeChunkRepository {

    List<KnowledgeChunk> findByDocument(String tenantId, String knowledgeBaseId, String documentId);

    void replaceByDocument(
            String tenantId,
            String knowledgeBaseId,
            String documentId,
            List<KnowledgeChunk> chunks);

    void deleteByDocument(String tenantId, String knowledgeBaseId, String documentId);

    long countByKnowledgeBase(String tenantId, String knowledgeBaseId);
}
