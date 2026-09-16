package com.xnlp.server.pipeline.dag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Builds stable topological execution batches from a validated DAG. */
public final class PipelineDagPlanner {

    private static final Comparator<PipelineDagNode> BY_NODE_ID = Comparator.comparing(PipelineDagNode::nodeId);

    private final PipelineDagValidator validator;

    public PipelineDagPlanner() {
        this(new PipelineDagValidator());
    }

    PipelineDagPlanner(PipelineDagValidator validator) {
        this.validator = validator;
    }

    public PipelineDagPlan plan(PipelineDag dag) {
        validator.validate(dag);

        Map<String, PipelineDagNode> nodesById = new HashMap<>();
        Map<String, Integer> indegrees = new HashMap<>();
        Map<String, TreeSet<String>> successors = new HashMap<>();
        Map<String, TreeSet<String>> predecessors = new HashMap<>();
        for (PipelineDagNode node : dag.nodes()) {
            nodesById.put(node.nodeId(), node);
            indegrees.put(node.nodeId(), 0);
            successors.put(node.nodeId(), new TreeSet<>());
            predecessors.put(node.nodeId(), new TreeSet<>());
        }
        for (PipelineDagEdge edge : dag.edges()) {
            successors.get(edge.sourceNodeId()).add(edge.targetNodeId());
            predecessors.get(edge.targetNodeId()).add(edge.sourceNodeId());
            indegrees.compute(edge.targetNodeId(), (ignored, value) -> value + 1);
        }

        TreeSet<String> ready = new TreeSet<>();
        indegrees.forEach((nodeId, degree) -> {
            if (degree == 0) {
                ready.add(nodeId);
            }
        });

        List<List<PipelineDagNode>> batches = new ArrayList<>();
        int plannedNodeCount = 0;
        while (!ready.isEmpty()) {
            List<String> batchNodeIds = List.copyOf(ready);
            ready.clear();

            List<PipelineDagNode> batch = batchNodeIds.stream()
                    .map(nodesById::get)
                    .sorted(BY_NODE_ID)
                    .toList();
            batches.add(batch);
            plannedNodeCount += batch.size();

            TreeSet<String> next = new TreeSet<>();
            for (String nodeId : batchNodeIds) {
                for (String successor : successors.get(nodeId)) {
                    int remaining = indegrees.compute(successor, (ignored, value) -> value - 1);
                    if (remaining == 0) {
                        next.add(successor);
                    }
                }
            }
            ready.addAll(next);
        }

        if (plannedNodeCount != dag.nodes().size()) {
            throw PipelineDagException.cycle("Pipeline DAG contains a cycle");
        }

        Map<String, List<String>> predecessorNodeIds = new LinkedHashMap<>();
        nodesById.keySet().stream().sorted().forEach(nodeId ->
                predecessorNodeIds.put(nodeId, List.copyOf(predecessors.get(nodeId))));
        return new PipelineDagPlan(batches, predecessorNodeIds);
    }
}
