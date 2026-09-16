package com.xnlp.core.rag.eval;

/** Aggregate quality and latency metrics for a retrieval evaluation run. */
public record RetrievalEvaluationMetrics(
        double recallAtK,
        double mrr,
        double ndcgAtK,
        double averageLatencyMs,
        long p95LatencyMs) {

    public RetrievalEvaluationMetrics {
        validateUnitInterval(recallAtK, "recallAtK");
        validateUnitInterval(mrr, "mrr");
        validateUnitInterval(ndcgAtK, "ndcgAtK");
        if (!Double.isFinite(averageLatencyMs) || averageLatencyMs < 0) {
            throw new IllegalArgumentException("averageLatencyMs must be finite and non-negative");
        }
        if (p95LatencyMs < 0) {
            throw new IllegalArgumentException("p95LatencyMs must not be negative");
        }
    }

    private static void validateUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be finite and between 0 and 1");
        }
    }
}
