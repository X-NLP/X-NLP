package com.xnlp.server.pipeline.dag;

/** A directed dependency from one pipeline node to another. */
public record PipelineDagEdge(String sourceNodeId, String targetNodeId) {

    public PipelineDagEdge {
        if (sourceNodeId == null || sourceNodeId.isBlank()) {
            throw PipelineDagException.invalid("Pipeline edge sourceNodeId must not be blank");
        }
        if (targetNodeId == null || targetNodeId.isBlank()) {
            throw PipelineDagException.invalid("Pipeline edge targetNodeId must not be blank");
        }
    }
}
