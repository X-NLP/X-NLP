package com.xnlp.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request contract for creating a knowledge base. */
public record KnowledgeBaseCreateRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 120, message = "name must not exceed 120 characters")
        String name,
        @Size(max = 2_000, message = "description must not exceed 2000 characters")
        String description,
        @Size(max = 190, message = "embeddingModel must not exceed 190 characters")
        String embeddingModel,
        @Valid ChunkPolicyRequest chunkPolicy) {

    public KnowledgeBaseCreateRequest {
        if (chunkPolicy == null) {
            chunkPolicy = new ChunkPolicyRequest(null, null, null);
        }
    }
}
