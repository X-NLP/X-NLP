package com.xnlp.server.dto.pipeline;

/** Persisted directed edge returned in stable definition order. */
public record PipelineEdgeResponse(
        String sourceNodeId,
        String targetNodeId,
        String sourceOutput,
        String targetInput) {
}
