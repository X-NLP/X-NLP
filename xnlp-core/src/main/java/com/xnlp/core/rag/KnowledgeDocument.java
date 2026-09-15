package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable source document and indexing state. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeDocument(
        String id,
        String knowledgeBaseId,
        String externalId,
        String title,
        SourceType sourceType,
        String sourceUri,
        String content,
        String contentChecksum,
        long version,
        IndexStatus indexStatus,
        String errorMessage,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public KnowledgeDocument {
        id = requireText(id, "id");
        knowledgeBaseId = requireText(knowledgeBaseId, "knowledgeBaseId");
        title = requireText(title, "title");
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        content = requireContent(content);
        contentChecksum = requireText(contentChecksum, "contentChecksum");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        indexStatus = Objects.requireNonNullElse(indexStatus, IndexStatus.PENDING);
        metadata = immutableMetadata(metadata);
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
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

    public enum SourceType {
        TEXT,
        FILE,
        URL,
        API
    }

    public enum IndexStatus {
        PENDING,
        INDEXING,
        INDEXED,
        FAILED
    }
}
