package com.xnlp.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/** Partial knowledge-base update. Embedding model changes require reindex. */
public record KnowledgeBaseUpdateRequest(
        @Size(min = 1, max = 120, message = "name must contain between 1 and 120 characters")
        String name,
        @Size(max = 2_000, message = "description must not exceed 2000 characters")
        String description,
        @Size(min = 1, max = 190, message = "embeddingModel must contain between 1 and 190 characters")
        String embeddingModel,
        @Valid ChunkPolicyRequest chunkPolicy,
        Boolean reindex) {

    @AssertTrue(message = "at least one update field must be supplied")
    public boolean isUpdatePresent() {
        return name != null || description != null || embeddingModel != null || chunkPolicy != null;
    }
}
