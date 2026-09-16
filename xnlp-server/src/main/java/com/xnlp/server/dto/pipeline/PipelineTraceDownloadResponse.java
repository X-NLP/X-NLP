package com.xnlp.server.dto.pipeline;

import java.time.Instant;
import java.util.List;

/** Complete downloadable JSON trace for a terminal pipeline run. */
public record PipelineTraceDownloadResponse(
        String traceId,
        String runId,
        String pipelineId,
        long pipelineVersion,
        String status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        List<PipelineNodeTraceResponse> nodes,
        List<PipelineEventResponse> events) {

    public PipelineTraceDownloadResponse {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
