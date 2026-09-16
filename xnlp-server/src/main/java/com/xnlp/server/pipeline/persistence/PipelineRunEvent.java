package com.xnlp.server.pipeline.persistence;

import java.time.Instant;

public record PipelineRunEvent(
        String tenantId,
        String runId,
        long sequence,
        String eventType,
        String nodeId,
        Integer attempt,
        String payloadJson,
        Instant occurredAt) {

    public PipelineRunEvent {
        tenantId = PipelinePersistenceSupport.text(tenantId, "tenantId", 64);
        runId = PipelinePersistenceSupport.text(runId, "runId", 64);
        eventType = PipelinePersistenceSupport.text(eventType, "eventType", 96);
        nodeId = PipelinePersistenceSupport.nullableText(nodeId, "nodeId", 96);
        payloadJson = PipelinePersistenceSupport.nullableText(payloadJson, "payloadJson", 2_000_000);
        PipelinePersistenceSupport.instant(occurredAt, "occurredAt");
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        if (attempt != null && attempt < 1) throw new IllegalArgumentException("attempt must be positive");
    }
}
