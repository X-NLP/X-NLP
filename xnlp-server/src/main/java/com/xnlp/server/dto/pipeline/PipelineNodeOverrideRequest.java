package com.xnlp.server.dto.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Per-run overrides for an existing node; capability and graph topology are immutable at run time. */
public record PipelineNodeOverrideRequest(
        @Size(max = 100, message = "node parameters must not contain more than 100 entries")
        Map<String, Object> parameters,
        @Min(value = 1, message = "timeoutSeconds must be at least 1")
        @Max(value = 3_600, message = "timeoutSeconds must not exceed 3600")
        Integer timeoutSeconds,
        @Valid PipelineRetryPolicyRequest retry) {

    public PipelineNodeOverrideRequest {
        parameters = parameters == null ? null : Map.copyOf(parameters);
    }

    @AssertTrue(message = "at least one node override field must be supplied")
    public boolean isOverridePresent() {
        return parameters != null || timeoutSeconds != null || retry != null;
    }
}
