package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/** A deterministic, source-addressable document chunk. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeChunk(
        String id,
        String knowledgeBaseId,
        String documentId,
        int sequence,
        String content,
        String contentChecksum,
        int startOffset,
        int endOffset,
        Map<String, Object> metadata) {

    public KnowledgeChunk {
        id = requireText(id, "id");
        knowledgeBaseId = requireText(knowledgeBaseId, "knowledgeBaseId");
        documentId = requireText(documentId, "documentId");
        content = requireContent(content);
        contentChecksum = requireText(contentChecksum, "contentChecksum");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("chunk offsets must describe a non-empty source range");
        }
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
