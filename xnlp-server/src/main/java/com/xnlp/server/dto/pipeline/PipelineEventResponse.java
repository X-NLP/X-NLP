package com.xnlp.server.dto.pipeline;

import java.time.Instant;
import java.util.Map;

/** Resumable SSE event contract; {@code id} is used by Last-Event-ID replay. */
public record PipelineEventResponse(
        long id,
        String runId,
        String type,
        String status,
        String nodeId,
        Integer attempt,
        Map<String, Object> detail,
        Instant occurredAt) {

    public PipelineEventResponse {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
