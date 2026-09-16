package com.xnlp.server.pipeline.persistence;

public record PipelineNodeDefinition(
        String nodeId,
        String capabilityId,
        String configJson,
        long timeoutMillis,
        int maxAttempts) {

    public PipelineNodeDefinition {
        nodeId = PipelinePersistenceSupport.text(nodeId, "nodeId", 96);
        capabilityId = PipelinePersistenceSupport.text(capabilityId, "capabilityId", 190);
        configJson = PipelinePersistenceSupport.nullableText(configJson, "configJson", 2_000_000);
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis must be positive");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
    }
}
