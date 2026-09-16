package com.xnlp.server.pipeline.persistence;

public record PipelineEdgeDefinition(
        String sourceNodeId,
        String targetNodeId,
        String sourceOutput,
        String targetInput) {

    public PipelineEdgeDefinition {
        sourceNodeId = PipelinePersistenceSupport.text(sourceNodeId, "sourceNodeId", 96);
        targetNodeId = PipelinePersistenceSupport.text(targetNodeId, "targetNodeId", 96);
        sourceOutput = PipelinePersistenceSupport.nullableText(sourceOutput, "sourceOutput", 190);
        targetInput = PipelinePersistenceSupport.nullableText(targetInput, "targetInput", 190);
        if (sourceNodeId.equals(targetNodeId)) throw new IllegalArgumentException("pipeline edge cannot reference itself");
    }
}
