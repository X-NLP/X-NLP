package com.xnlp.server.dto;

import com.xnlp.core.rag.ChunkPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Validated, portable document chunking configuration. */
public record ChunkPolicyRequest(
        @Min(value = 100, message = "maxCharacters must be at least 100")
        @Max(value = 100_000, message = "maxCharacters must not exceed 100000")
        Integer maxCharacters,
        @Min(value = 0, message = "overlapCharacters must not be negative")
        @Max(value = 99_999, message = "overlapCharacters must not exceed 99999")
        Integer overlapCharacters,
        @NotNull(message = "separatorMode must be specified")
        ChunkPolicy.SeparatorMode separatorMode) {

    public ChunkPolicyRequest {
        if (maxCharacters == null) {
            maxCharacters = ChunkPolicy.DEFAULT_MAX_CHARACTERS;
        }
        if (overlapCharacters == null) {
            overlapCharacters = ChunkPolicy.DEFAULT_OVERLAP_CHARACTERS;
        }
        if (separatorMode == null) {
            separatorMode = ChunkPolicy.SeparatorMode.PARAGRAPH;
        }
    }

    @jakarta.validation.constraints.AssertTrue(message = "overlapCharacters must be less than maxCharacters")
    public boolean isOverlapValid() {
        return maxCharacters == null || overlapCharacters == null || overlapCharacters < maxCharacters;
    }

    public ChunkPolicy toModel() {
        return new ChunkPolicy(maxCharacters, overlapCharacters, separatorMode);
    }
}
