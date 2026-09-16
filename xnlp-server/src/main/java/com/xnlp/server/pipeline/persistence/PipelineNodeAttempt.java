package com.xnlp.server.pipeline.persistence;

import java.time.Instant;
import java.util.Objects;

public record PipelineNodeAttempt(
        String tenantId,
        String runId,
        String nodeId,
        int attempt,
        NodeAttemptStatus status,
        String inputJson,
        String outputJson,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {

    public PipelineNodeAttempt {
        tenantId = PipelinePersistenceSupport.text(tenantId, "tenantId", 64);
        runId = PipelinePersistenceSupport.text(runId, "runId", 64);
        nodeId = PipelinePersistenceSupport.text(nodeId, "nodeId", 96);
        Objects.requireNonNull(status, "status");
        inputJson = PipelinePersistenceSupport.nullableText(inputJson, "inputJson", 2_000_000);
        outputJson = PipelinePersistenceSupport.nullableText(outputJson, "outputJson", 2_000_000);
        errorCode = PipelinePersistenceSupport.nullableText(errorCode, "errorCode", 96);
        errorMessage = PipelinePersistenceSupport.nullableText(errorMessage, "errorMessage", 65_535);
        PipelinePersistenceSupport.instant(startedAt, "startedAt");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        if (status.terminal()) PipelinePersistenceSupport.instant(completedAt, "completedAt");
        else if (completedAt != null) throw new IllegalArgumentException("running attempt cannot have completedAt");
    }
}
