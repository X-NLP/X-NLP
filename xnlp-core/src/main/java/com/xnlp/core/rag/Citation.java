package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Citation that must resolve to a chunk returned by the retrieval stage. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Citation(
        String documentId,
        String chunkId,
        String title,
        String sourceUri,
        String excerpt) {

    public Citation {
        documentId = requireText(documentId, "documentId");
        chunkId = requireText(chunkId, "chunkId");
        title = requireText(title, "title");
        excerpt = requireText(excerpt, "excerpt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
