package com.xnlp.core.repository;

import com.xnlp.core.rag.IngestionJob;

import java.util.List;
import java.util.Optional;

/** Tenant-explicit persistence contract for durable ingestion progress. */
public interface IngestionJobRepository {

    List<IngestionJob> findByKnowledgeBase(String tenantId, String knowledgeBaseId);

    Optional<IngestionJob> findById(String tenantId, String id);

    IngestionJob save(String tenantId, IngestionJob job);
}
