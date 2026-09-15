package com.xnlp.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request contract for a model benchmark run. */
public record BenchmarkRequest(
        @Min(value = 1, message = "requests must be at least 1")
        @Max(value = 10_000, message = "requests must not exceed 10000")
        Integer requests,
        @Min(value = 1, message = "concurrency must be at least 1")
        @Max(value = 256, message = "concurrency must not exceed 256")
        Integer concurrency,
        @NotBlank(message = "text must not be blank")
        @Size(max = 10_000, message = "text must not exceed 10000 characters")
        String text) {

    public BenchmarkRequest {
        if (requests == null) {
            requests = 100;
        }
        if (concurrency == null) {
            concurrency = 4;
        }
        if (text == null) {
            text = "The future of natural language processing is bright.";
        }
    }
}
