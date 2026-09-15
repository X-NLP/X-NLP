package com.xnlp.core.repository;

import com.xnlp.core.rag.eval.RetrievalEvaluationRun;
import com.xnlp.core.rag.eval.RetrievalEvaluationSampleResult;

import java.util.List;
import java.util.Optional;

/** Tenant-explicit persistence contract for retrieval evaluation evidence. */
public interface RetrievalEvaluationRepository {

    List<RetrievalEvaluationRun> findRuns(String tenantId, String knowledgeBaseId);

    Optional<RetrievalEvaluationRun> findRun(String tenantId, String knowledgeBaseId, String runId);

    boolean hasActiveRun(String tenantId, String knowledgeBaseId);

    RetrievalEvaluationRun saveRun(String tenantId, RetrievalEvaluationRun run);

    RetrievalEvaluationSampleResult saveSampleResult(
            String tenantId,
            String knowledgeBaseId,
            RetrievalEvaluationSampleResult result,
            int sequence);

    List<RetrievalEvaluationSampleResult> findSampleResults(
            String tenantId,
            String knowledgeBaseId,
            String runId);

    void deleteByKnowledgeBase(String tenantId, String knowledgeBaseId);
}
