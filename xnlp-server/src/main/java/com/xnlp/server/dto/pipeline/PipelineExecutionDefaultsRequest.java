package com.xnlp.server.dto.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Optional execution defaults inherited by nodes that do not override them. */
public record PipelineExecutionDefaultsRequest(
        @Min(value = 1, message = "timeoutSeconds must be at least 1")
        @Max(value = 3_600, message = "timeoutSeconds must not exceed 3600")
        Integer timeoutSeconds,
        @Valid PipelineRetryPolicyRequest retry,
        @Min(value = 1, message = "maxParallelism must be at least 1")
        @Max(value = 64, message = "maxParallelism must not exceed 64")
        Integer maxParallelism) {
}
