package com.xnlp.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request for context-constrained question answering. */
public record NlpQaRequest(
        @Size(max = 120, message = "modelName must not exceed 120 characters") String modelName,
        @NotBlank(message = "context must not be blank")
        @Size(max = 200_000, message = "context must not exceed 200000 characters") String context,
        @NotBlank(message = "question must not be blank")
        @Size(max = 10_000, message = "question must not exceed 10000 characters") String question) {
}
