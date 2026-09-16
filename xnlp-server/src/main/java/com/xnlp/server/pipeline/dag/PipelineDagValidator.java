package com.xnlp.server.pipeline.dag;

import java.util.HashSet;
import java.util.Set;

/** Performs structural validation that is independent from execution concerns. */
public final class PipelineDagValidator {

    public void validate(PipelineDag dag) {
        if (dag == null) {
            throw PipelineDagException.invalid("Pipeline DAG must not be null");
        }
        if (dag.nodes().isEmpty()) {
            throw PipelineDagException.invalid("Pipeline DAG must contain at least one node");
        }

        Set<String> nodeIds = new HashSet<>();
        for (PipelineDagNode node : dag.nodes()) {
            if (!nodeIds.add(node.nodeId())) {
                throw PipelineDagException.invalid("Duplicate pipeline nodeId: " + node.nodeId());
            }
        }

        Set<PipelineDagEdge> edges = new HashSet<>();
        for (PipelineDagEdge edge : dag.edges()) {
            if (!nodeIds.contains(edge.sourceNodeId())) {
                throw PipelineDagException.invalid(
                        "Pipeline edge source does not exist: " + edge.sourceNodeId());
            }
            if (!nodeIds.contains(edge.targetNodeId())) {
                throw PipelineDagException.invalid(
                        "Pipeline edge target does not exist: " + edge.targetNodeId());
            }
            if (edge.sourceNodeId().equals(edge.targetNodeId())) {
                throw PipelineDagException.cycle("Pipeline node cannot depend on itself: " + edge.sourceNodeId());
            }
            if (!edges.add(edge)) {
                throw PipelineDagException.invalid(
                        "Duplicate pipeline edge: " + edge.sourceNodeId() + " -> " + edge.targetNodeId());
            }
        }
    }
}
