package com.xnlp.core.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider-neutral vector search request with explicit tenant scope. */
public record VectorSearchRequest(
        String tenantId,
        String knowledgeBaseId,
        String embeddingModel,
        float[] queryVector,
        int topK,
        Double minScore,
        Map<String, Object> filter) {

    public VectorSearchRequest {
        tenantId = requireText(tenantId, "tenantId");
        knowledgeBaseId = requireText(knowledgeBaseId, "knowledgeBaseId");
        embeddingModel = requireText(embeddingModel, "embeddingModel");
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be empty");
        }
        validateFinite(queryVector, "queryVector");
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        if (minScore != null && (!Double.isFinite(minScore) || minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
        queryVector = queryVector.clone();
        filter = filter == null || filter.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(filter));
    }

    @Override
    public float[] queryVector() {
        return queryVector.clone();
    }

    private static void validateFinite(float[] values, String field) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(field + " must contain only finite values");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
