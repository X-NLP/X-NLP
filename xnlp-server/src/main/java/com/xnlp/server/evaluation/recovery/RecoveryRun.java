package com.xnlp.server.evaluation.recovery;

import java.time.Instant;
import java.util.Objects;

public record RecoveryRun(
        String tenantId,
        String runId,
        String datasetId,
        long datasetVersion,
        RecoveryRunStatus status,
        String rootRunId,
        String parentRunId,
        int attempt,
        boolean retryFailedOnly,
        int totalSamples,
        String actorId,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public RecoveryRun {
        tenantId = EvaluationRecoverySupport.tenantId(tenantId);
        runId = EvaluationRecoverySupport.text(runId, "runId", 64);
        datasetId = EvaluationRecoverySupport.text(datasetId, "datasetId", 64);
        rootRunId = EvaluationRecoverySupport.text(rootRunId, "rootRunId", 64);
        parentRunId = EvaluationRecoverySupport.nullableText(parentRunId, "parentRunId", 64);
        actorId = EvaluationRecoverySupport.text(actorId, "actorId", 190);
        errorMessage = EvaluationRecoverySupport.nullableText(errorMessage, "errorMessage", 65535);
        Objects.requireNonNull(status, "status");
        EvaluationRecoverySupport.instant(createdAt, "createdAt");
        EvaluationRecoverySupport.instant(updatedAt, "updatedAt");
        if (datasetVersion < 0) throw new IllegalArgumentException("datasetVersion must not be negative");
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
        if (totalSamples < 0) throw new IllegalArgumentException("totalSamples must not be negative");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (attempt == 0) {
            if (parentRunId != null) throw new IllegalArgumentException("initial run must not have a parentRunId");
            if (!rootRunId.equals(runId)) throw new IllegalArgumentException("initial run must be its own root");
            if (retryFailedOnly) throw new IllegalArgumentException("initial run cannot be a failed-only retry");
        } else {
            if (parentRunId == null) throw new IllegalArgumentException("retry run requires parentRunId");
            if (parentRunId.equals(runId)) throw new IllegalArgumentException("retry run cannot parent itself");
        }
        if (status.terminal()) {
            EvaluationRecoverySupport.instant(completedAt, "completedAt");
            if (completedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("completedAt must not precede createdAt");
            }
        } else if (completedAt != null) {
            throw new IllegalArgumentException("non-terminal run cannot have completedAt");
        }
        if (status != RecoveryRunStatus.FAILED && errorMessage != null) {
            throw new IllegalArgumentException("errorMessage is only valid for failed runs");
        }
    }
}
