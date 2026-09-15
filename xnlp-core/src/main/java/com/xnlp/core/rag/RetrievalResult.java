package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Auditable retrieval response independent of a concrete vector database. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievalResult(
        String query,
        List<RetrievalMatch> matches,
        String embeddingModel,
        String reranker,
        long elapsedMs,
        String traceId) {

    public RetrievalResult {
        query = requireText(query, "query");
        embeddingModel = requireText(embeddingModel, "embeddingModel");
        matches = matches == null ? List.of() : List.copyOf(matches);
        if (elapsedMs < 0) {
            throw new IllegalArgumentException("elapsedMs must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
