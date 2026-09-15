package com.xnlp.core.repository;

import com.xnlp.core.rag.KnowledgeDocument;

import java.util.List;
import java.util.Optional;

/** Tenant-explicit persistence contract for source documents and indexing state. */
public interface KnowledgeDocumentRepository {

    List<KnowledgeDocument> findByKnowledgeBase(String tenantId, String knowledgeBaseId);

    Optional<KnowledgeDocument> findById(String tenantId, String knowledgeBaseId, String id);

    Optional<KnowledgeDocument> findByExternalId(String tenantId, String knowledgeBaseId, String externalId);

    KnowledgeDocument save(String tenantId, KnowledgeDocument document);

    boolean deleteById(String tenantId, String knowledgeBaseId, String id);
}
