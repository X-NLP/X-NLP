package com.xnlp.server.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request contract for retrieval-augmented generation. */
public record RagRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 20_000, message = "message must not exceed 20000 characters")
        String message,
        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 100, message = "topK must not exceed 100")
        Integer topK,
        @DecimalMin(value = "0.0", message = "minScore must be at least 0")
        @DecimalMax(value = "1.0", message = "minScore must not exceed 1")
        Double minScore,
        @Min(value = 1, message = "maxContextChunks must be at least 1")
        @Max(value = 50, message = "maxContextChunks must not exceed 50")
        Integer maxContextChunks,
        @Size(max = 190, message = "conversationId must not exceed 190 characters")
        String conversationId,
        @Size(max = 20_000, message = "systemPrompt must not exceed 20000 characters")
        String systemPrompt,
        InsufficientContextPolicy insufficientContextPolicy,
        @Min(value = 100, message = "timeoutMs must be at least 100")
        @Max(value = 120_000, message = "timeoutMs must not exceed 120000")
        Long timeoutMs) {

    public RagRequest {
        if (topK == null) {
            topK = 10;
        }
        if (maxContextChunks == null) {
            maxContextChunks = Math.min(topK, 8);
        }
        if (insufficientContextPolicy == null) {
            insufficientContextPolicy = InsufficientContextPolicy.REJECT;
        }
    }

    public RagRequest(
            String message,
            Integer topK,
            Double minScore,
            Integer maxContextChunks,
            String conversationId,
            String systemPrompt) {
        this(message, topK, minScore, maxContextChunks, conversationId, systemPrompt, null, null);
    }

    @jakarta.validation.constraints.AssertTrue(message = "maxContextChunks must not exceed topK")
    public boolean isContextLimitValid() {
        return maxContextChunks == null || topK == null || maxContextChunks <= topK;
    }

    public enum InsufficientContextPolicy {
        REJECT,
        ANSWER_WITH_LOW_CONFIDENCE
    }
}
