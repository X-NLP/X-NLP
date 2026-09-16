package com.xnlp.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Common model/text request used by prompt-backed NLP operations. */
public record NlpTextRequest(
        @Size(max = 120, message = "modelName must not exceed 120 characters") String modelName,
        @NotBlank(message = "text must not be blank")
        @Size(max = 100_000, message = "text must not exceed 100000 characters") String text) {
}
