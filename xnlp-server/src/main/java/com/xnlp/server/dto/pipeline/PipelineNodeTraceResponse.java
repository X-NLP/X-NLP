package com.xnlp.server.dto.pipeline;

import java.util.List;
import java.util.Map;

/** Node-level trace with every persisted attempt in ascending attempt order. */
public record PipelineNodeTraceResponse(
        String nodeId,
        String capability,
        String status,
        List<PipelineNodeAttemptResponse> attempts,
        Map<String, Object> output,
        String errorCode,
        String errorMessage) {

    public PipelineNodeTraceResponse {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        output = output == null ? Map.of() : Map.copyOf(output);
    }
}
