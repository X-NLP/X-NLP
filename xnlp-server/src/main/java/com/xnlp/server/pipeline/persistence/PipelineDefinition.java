package com.xnlp.server.pipeline.persistence;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PipelineDefinition(
        String tenantId,
        String id,
        String name,
        String description,
        long version,
        List<PipelineNodeDefinition> nodes,
        List<PipelineEdgeDefinition> edges,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public PipelineDefinition {
        tenantId = PipelinePersistenceSupport.text(tenantId, "tenantId", 64);
        id = PipelinePersistenceSupport.text(id, "id", 64);
        name = PipelinePersistenceSupport.text(name, "name", 190);
        description = PipelinePersistenceSupport.nullableText(description, "description", 65_535);
        createdBy = PipelinePersistenceSupport.text(createdBy, "createdBy", 190);
        PipelinePersistenceSupport.instant(createdAt, "createdAt");
        PipelinePersistenceSupport.instant(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        edges = List.copyOf(edges == null ? List.of() : edges);
        Set<String> nodeIds = new HashSet<>();
        for (PipelineNodeDefinition node : nodes) {
            if (!nodeIds.add(node.nodeId())) throw new IllegalArgumentException("duplicate nodeId: " + node.nodeId());
        }
        Set<PipelineEdgeDefinition> uniqueEdges = new HashSet<>();
        for (PipelineEdgeDefinition edge : edges) {
            if (!nodeIds.contains(edge.sourceNodeId()) || !nodeIds.contains(edge.targetNodeId())) {
                throw new IllegalArgumentException("pipeline edge references an unknown node");
            }
            if (!uniqueEdges.add(edge)) throw new IllegalArgumentException("duplicate pipeline edge");
        }
    }
}
