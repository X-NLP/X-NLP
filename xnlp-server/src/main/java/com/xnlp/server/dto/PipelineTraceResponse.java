package com.xnlp.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Structured execution result used by the Pipeline Canvas and API clients. */
public record PipelineTraceResponse(
        String traceId,
        String status,
        String inputText,
        String outputText,
        String language,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        List<NodeTrace> nodes) {

    public record NodeTrace(
            String id,
            String capability,
            String name,
            String status,
            String inputText,
            String outputText,
            long durationMs,
            Map<String, Object> result,
            String errorMessage) {
    }
}
