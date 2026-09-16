package com.xnlp.server.dto.pipeline;

import java.time.Instant;
import java.util.List;

/** Public pipeline definition contract. Tenant identity is deliberately omitted. */
public record PipelineResponse(
        String id,
        long version,
        String name,
        String description,
        String status,
        List<PipelineNodeResponse> nodes,
        List<PipelineEdgeResponse> edges,
        PipelineExecutionDefaultsResponse defaults,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public PipelineResponse {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
