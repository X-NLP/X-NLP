package com.xnlp.core.rag.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xnlp.core.rag.RetrievalMatch;

import java.util.LinkedHashSet;
import java.util.List;

/** Persisted sample-level evidence used to diagnose misses, scores and reranking. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievalEvaluationSampleResult(
        String id,
        String runId,
        String sampleId,
        String query,
        List<String> relevantChunkIds,
        List<RetrievalMatch> rawMatches,
        List<RetrievalMatch> finalMatches,
        double recallAtK,
        double reciprocalRank,
        double ndcgAtK,
        long latencyMs,
        boolean miss,
        boolean rerankChanged,
        String traceId) {

    public RetrievalEvaluationSampleResult {
        id = requireText(id, "id");
        runId = requireText(runId, "runId");
        sampleId = requireText(sampleId, "sampleId");
        query = requireText(query, "query");
        if (relevantChunkIds == null || relevantChunkIds.isEmpty()) {
            throw new IllegalArgumentException("relevantChunkIds must not be empty");
        }
        relevantChunkIds = List.copyOf(new LinkedHashSet<>(relevantChunkIds.stream()
                .map(value -> requireText(value, "relevantChunkId"))
                .toList()));
        rawMatches = rawMatches == null ? List.of() : List.copyOf(rawMatches);
        finalMatches = finalMatches == null ? List.of() : List.copyOf(finalMatches);
        validateMetric(recallAtK, "recallAtK");
        validateMetric(reciprocalRank, "reciprocalRank");
        validateMetric(ndcgAtK, "ndcgAtK");
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }

    private static void validateMetric(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be finite and between 0 and 1");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
