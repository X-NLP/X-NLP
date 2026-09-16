package com.xnlp.server.dto.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Persistent pipeline run summary and latest node states. */
public record PipelineRunResponse(
        String id,
        String pipelineId,
        long pipelineVersion,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        boolean cancelRequested,
        List<PipelineNodeRunResponse> nodes,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public PipelineRunResponse {
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
