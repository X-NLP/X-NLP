package com.xnlp.server.pipeline.dag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An immutable executable node in a pipeline DAG. */
public record PipelineDagNode(
        String nodeId,
        String capability,
        Map<String, Object> configuration) {

    public PipelineDagNode {
        if (nodeId == null || nodeId.isBlank()) {
            throw PipelineDagException.invalid("Pipeline nodeId must not be blank");
        }
        if (capability == null || capability.isBlank()) {
            throw PipelineDagException.invalid("Pipeline node capability must not be blank: " + nodeId);
        }
        if (configuration == null) {
            throw PipelineDagException.invalid("Pipeline node configuration must not be null: " + nodeId);
        }
        configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }

    public PipelineDagNode(String nodeId, String capability) {
        this(nodeId, capability, Map.of());
    }
}
