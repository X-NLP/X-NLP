package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/** Search result retaining source identity and both retrieval stages' scores. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievalMatch(
        String documentId,
        String chunkId,
        String title,
        String content,
        String sourceUri,
        double score,
        Double rerankScore,
        Map<String, Object> metadata) {

    public RetrievalMatch {
        documentId = requireText(documentId, "documentId");
        chunkId = requireText(chunkId, "chunkId");
        title = requireText(title, "title");
        content = requireText(content, "content");
        validateScore(score, "score");
        if (rerankScore != null) {
            validateScore(rerankScore, "rerankScore");
        }
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public double effectiveScore() {
        return rerankScore == null ? score : rerankScore;
    }

    private static void validateScore(double value, String field) {
        if (!Double.isFinite(value) || value < -1 || value > 1) {
            throw new IllegalArgumentException(field + " must be finite and between -1 and 1");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
