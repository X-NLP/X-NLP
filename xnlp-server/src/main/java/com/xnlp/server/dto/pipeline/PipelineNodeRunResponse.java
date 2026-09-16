package com.xnlp.server.dto.pipeline;

import java.time.Instant;
import java.util.Map;

/** Latest persisted state of one node in a pipeline run. */
public record PipelineNodeRunResponse(
        String nodeId,
        String capability,
        String status,
        int attempt,
        int maxAttempts,
        Map<String, Object> input,
        Map<String, Object> output,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Long durationMs) {

    public PipelineNodeRunResponse {
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
    }
}
