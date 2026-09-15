package com.xnlp.core.rag.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable configuration, progress and aggregate result of a retrieval evaluation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievalEvaluationRun(
        String id,
        String knowledgeBaseId,
        String datasetId,
        Status status,
        int topK,
        Double minScore,
        Map<String, Object> filter,
        boolean rerank,
        int rerankTopN,
        int totalSamples,
        int processedSamples,
        RetrievalEvaluationMetrics metrics,
        String errorMessage,
        Instant createdAt,
        Instant completedAt) {

    public RetrievalEvaluationRun {
        id = requireText(id, "id");
        knowledgeBaseId = requireText(knowledgeBaseId, "knowledgeBaseId");
        if (datasetId != null && datasetId.isBlank()) {
            datasetId = null;
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        if (minScore != null && (!Double.isFinite(minScore) || minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
        if (rerankTopN < 1 || rerankTopN > topK) {
            throw new IllegalArgumentException("rerankTopN must be between 1 and topK");
        }
        if (totalSamples < 0 || processedSamples < 0 || processedSamples > totalSamples) {
            throw new IllegalArgumentException("sample progress is invalid");
        }
        filter = filter == null || filter.isEmpty()
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(filter));
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public double progressPercent() {
        return totalSamples == 0 ? 0 : processedSamples * 100.0 / totalSamples;
    }

    public enum Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
