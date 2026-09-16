package com.xnlp.server.pipeline.persistence;

import java.time.Instant;
import java.util.Objects;

public record PipelineRun(
        String tenantId,
        String runId,
        String pipelineId,
        long pipelineVersion,
        PipelineRunStatus status,
        String inputJson,
        String outputJson,
        String errorCode,
        String errorMessage,
        boolean cancelRequested,
        long revision,
        String actorId,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt) {

    public PipelineRun {
        tenantId = PipelinePersistenceSupport.text(tenantId, "tenantId", 64);
        runId = PipelinePersistenceSupport.text(runId, "runId", 64);
        pipelineId = PipelinePersistenceSupport.text(pipelineId, "pipelineId", 64);
        actorId = PipelinePersistenceSupport.text(actorId, "actorId", 190);
        Objects.requireNonNull(status, "status");
        inputJson = PipelinePersistenceSupport.nullableText(inputJson, "inputJson", 2_000_000);
        outputJson = PipelinePersistenceSupport.nullableText(outputJson, "outputJson", 2_000_000);
        errorCode = PipelinePersistenceSupport.nullableText(errorCode, "errorCode", 96);
        errorMessage = PipelinePersistenceSupport.nullableText(errorMessage, "errorMessage", 65_535);
        PipelinePersistenceSupport.instant(createdAt, "createdAt");
        PipelinePersistenceSupport.instant(updatedAt, "updatedAt");
        if (pipelineVersion < 0) throw new IllegalArgumentException("pipelineVersion must not be negative");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (startedAt != null && startedAt.isBefore(createdAt)) throw new IllegalArgumentException("startedAt must not precede createdAt");
        if (status.terminal()) {
            PipelinePersistenceSupport.instant(completedAt, "completedAt");
        } else if (completedAt != null) {
            throw new IllegalArgumentException("non-terminal run cannot have completedAt");
        }
        if (status == PipelineRunStatus.CANCELLING && !cancelRequested) {
            throw new IllegalArgumentException("cancelling run must have cancelRequested=true");
        }
    }
}
