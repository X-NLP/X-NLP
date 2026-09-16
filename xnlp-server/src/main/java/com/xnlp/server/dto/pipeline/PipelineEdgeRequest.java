package com.xnlp.server.dto.pipeline;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Directed data dependency between two DAG nodes. */
public record PipelineEdgeRequest(
        @NotBlank(message = "sourceNodeId must not be blank")
        @Size(max = 120, message = "sourceNodeId must not exceed 120 characters")
        String sourceNodeId,
        @NotBlank(message = "targetNodeId must not be blank")
        @Size(max = 120, message = "targetNodeId must not exceed 120 characters")
        String targetNodeId,
        @Size(max = 120, message = "sourceOutput must not exceed 120 characters")
        String sourceOutput,
        @Size(max = 120, message = "targetInput must not exceed 120 characters")
        String targetInput) {
}
