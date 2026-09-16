package com.xnlp.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Structured request for component-based NLP analysis. */
public record NlpAnalyzeRequest(
        String task,
        String capability,
        @NotBlank(message = "text must not be blank")
        @Size(max = 100_000, message = "text must not exceed 100000 characters")
        String text,
        @Size(max = 100_000, message = "textPair must not exceed 100000 characters")
        String textPair,
        @Size(max = 32, message = "language must not exceed 32 characters")
        String language,
        Boolean coarse,
        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 100, message = "topK must not exceed 100")
        Integer topK,
        Object labels,
        @Min(value = 1, message = "maxLength must be at least 1")
        @Max(value = 10_000, message = "maxLength must not exceed 10000")
        Integer maxLength,
        String style,
        Boolean semantic) {
}
