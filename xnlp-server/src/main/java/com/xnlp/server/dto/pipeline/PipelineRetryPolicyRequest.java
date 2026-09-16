package com.xnlp.server.dto.pipeline;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Retry policy applied to one pipeline node attempt sequence. */
public record PipelineRetryPolicyRequest(
        @NotNull(message = "maxAttempts must be supplied")
        @Min(value = 1, message = "maxAttempts must be at least 1")
        @Max(value = 10, message = "maxAttempts must not exceed 10")
        Integer maxAttempts,
        @NotNull(message = "backoffMillis must be supplied")
        @Min(value = 0, message = "backoffMillis must not be negative")
        @Max(value = 300_000, message = "backoffMillis must not exceed 300000")
        Long backoffMillis) {
}
