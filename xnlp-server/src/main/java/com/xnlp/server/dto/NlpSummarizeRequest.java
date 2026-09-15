package com.xnlp.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request for text summarization. */
public record NlpSummarizeRequest(
        @Size(max = 120, message = "modelName must not exceed 120 characters") String modelName,
        @NotBlank(message = "text must not be blank")
        @Size(max = 100_000, message = "text must not exceed 100000 characters") String text,
        @Min(value = 1, message = "maxLength must be at least 1")
        @Max(value = 10_000, message = "maxLength must not exceed 10000") Integer maxLength) {
}
