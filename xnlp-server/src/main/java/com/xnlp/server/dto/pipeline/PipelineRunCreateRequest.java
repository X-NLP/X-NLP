package com.xnlp.server.dto.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Queues execution of the current persisted pipeline version. */
public record PipelineRunCreateRequest(
        @NotNull(message = "input must be supplied")
        @Size(max = 100, message = "input must not contain more than 100 entries")
        Map<String, Object> input,
        @Size(max = 256, message = "overrides must not contain more than 256 entries")
        Map<@Size(min = 1, max = 120, message = "override node id must contain between 1 and 120 characters")
                String, @Valid PipelineNodeOverrideRequest> overrides,
        @Size(max = 190, message = "idempotencyKey must not exceed 190 characters")
        String idempotencyKey) {

    public PipelineRunCreateRequest {
        input = input == null ? null : Map.copyOf(input);
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }
}
