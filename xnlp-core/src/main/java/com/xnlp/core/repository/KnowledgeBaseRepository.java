package com.xnlp.core.repository;

import com.xnlp.core.rag.KnowledgeBase;

import java.util.List;
import java.util.Optional;

/** Tenant-explicit persistence contract for knowledge-base descriptors. */
public interface KnowledgeBaseRepository {

    List<KnowledgeBase> findAll(String tenantId);

    Optional<KnowledgeBase> findById(String tenantId, String id);

    KnowledgeBase save(String tenantId, KnowledgeBase knowledgeBase);

    boolean deleteById(String tenantId, String id);
}
