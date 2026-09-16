package com.xnlp.server.dto.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** One capability node in a persistent pipeline DAG definition. */
public record PipelineNodeRequest(
        @NotBlank(message = "node id must not be blank")
        @Size(max = 120, message = "node id must not exceed 120 characters")
        String id,
        @NotBlank(message = "capability must not be blank")
        @Size(max = 190, message = "capability must not exceed 190 characters")
        String capability,
        @Size(max = 120, message = "node name must not exceed 120 characters")
        String name,
        @Size(max = 100, message = "node parameters must not contain more than 100 entries")
        Map<String, Object> parameters,
        @Min(value = 1, message = "timeoutSeconds must be at least 1")
        @Max(value = 3_600, message = "timeoutSeconds must not exceed 3600")
        Integer timeoutSeconds,
        @Valid PipelineRetryPolicyRequest retry) {

    public PipelineNodeRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
