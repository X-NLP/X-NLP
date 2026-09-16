package com.xnlp.server.pipeline.dag;

import java.util.List;

/** Immutable node and edge definition of a pipeline directed graph. */
public record PipelineDag(List<PipelineDagNode> nodes, List<PipelineDagEdge> edges) {

    public PipelineDag {
        if (nodes == null) {
            throw PipelineDagException.invalid("Pipeline nodes must not be null");
        }
        if (edges == null) {
            throw PipelineDagException.invalid("Pipeline edges must not be null");
        }
        if (nodes.stream().anyMatch(node -> node == null)) {
            throw PipelineDagException.invalid("Pipeline nodes must not contain null");
        }
        if (edges.stream().anyMatch(edge -> edge == null)) {
            throw PipelineDagException.invalid("Pipeline edges must not contain null");
        }
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
