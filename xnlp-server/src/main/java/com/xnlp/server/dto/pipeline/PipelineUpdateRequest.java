package com.xnlp.server.dto.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Replaces a pipeline DAG using optimistic version matching. */
public record PipelineUpdateRequest(
        @Positive(message = "version must be positive") long version,
        @NotBlank(message = "name must not be blank")
        @Size(max = 120, message = "name must not exceed 120 characters")
        String name,
        @Size(max = 2_000, message = "description must not exceed 2000 characters")
        String description,
        @NotEmpty(message = "nodes must not be empty")
        @Size(max = 256, message = "nodes must not contain more than 256 entries")
        List<@Valid PipelineNodeRequest> nodes,
        @Size(max = 2_048, message = "edges must not contain more than 2048 entries")
        List<@Valid PipelineEdgeRequest> edges,
        @Valid PipelineExecutionDefaultsRequest defaults) {

    public PipelineUpdateRequest {
        nodes = nodes == null ? null : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
