package com.xnlp.server.pipeline.dag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic execution plan. Nodes in one batch have no dependencies on each other and may run in parallel.
 */
public record PipelineDagPlan(
        List<List<PipelineDagNode>> batches,
        Map<String, List<String>> predecessorNodeIds) {

    public PipelineDagPlan {
        if (batches == null || predecessorNodeIds == null) {
            throw PipelineDagException.invalid("Pipeline DAG plan data must not be null");
        }
        List<List<PipelineDagNode>> copiedBatches = new ArrayList<>(batches.size());
        for (List<PipelineDagNode> batch : batches) {
            if (batch == null || batch.stream().anyMatch(node -> node == null)) {
                throw PipelineDagException.invalid("Pipeline DAG plan batches must not contain null");
            }
            copiedBatches.add(List.copyOf(batch));
        }
        batches = List.copyOf(copiedBatches);

        Map<String, List<String>> copiedPredecessors = new LinkedHashMap<>();
        predecessorNodeIds.forEach((nodeId, predecessors) -> {
            if (nodeId == null || predecessors == null || predecessors.stream().anyMatch(id -> id == null)) {
                throw PipelineDagException.invalid("Pipeline DAG predecessor data must not contain null");
            }
            copiedPredecessors.put(nodeId, List.copyOf(predecessors));
        });
        predecessorNodeIds = Collections.unmodifiableMap(copiedPredecessors);
    }

    public List<String> nodeIdsInExecutionOrder() {
        return batches.stream()
                .flatMap(List::stream)
                .map(PipelineDagNode::nodeId)
                .toList();
    }
}
