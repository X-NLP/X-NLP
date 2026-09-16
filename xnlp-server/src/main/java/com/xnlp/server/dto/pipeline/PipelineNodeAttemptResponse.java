package com.xnlp.server.dto.pipeline;

import java.time.Instant;
import java.util.Map;

/** Immutable trace record for one node attempt, including failures and timeouts. */
public record PipelineNodeAttemptResponse(
        int attempt,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Long durationMs) {

    public PipelineNodeAttemptResponse {
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
    }
}
