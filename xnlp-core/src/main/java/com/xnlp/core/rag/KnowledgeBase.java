package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

/** Immutable knowledge-base descriptor shared by persistence, REST and SDK layers. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeBase(
        String id,
        String name,
        String description,
        String embeddingModel,
        ChunkPolicy chunkPolicy,
        Status status,
        long documentCount,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt) {

    public KnowledgeBase {
        id = requireText(id, "id");
        name = requireText(name, "name");
        embeddingModel = requireText(embeddingModel, "embeddingModel");
        chunkPolicy = Objects.requireNonNullElseGet(chunkPolicy, ChunkPolicy::defaults);
        status = Objects.requireNonNullElse(status, Status.ACTIVE);
        if (documentCount < 0 || chunkCount < 0) {
            throw new IllegalArgumentException("knowledge-base counters must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public enum Status {
        ACTIVE,
        REINDEXING,
        ERROR
    }
}
