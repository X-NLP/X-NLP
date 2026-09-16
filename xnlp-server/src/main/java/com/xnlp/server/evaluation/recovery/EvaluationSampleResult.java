package com.xnlp.server.evaluation.recovery;

import java.time.Instant;
import java.util.Objects;

public record EvaluationSampleResult(
        String tenantId,
        String runId,
        String sampleId,
        int sequence,
        SampleResultStatus status,
        String expectedOutput,
        String actualOutput,
        String scoreJson,
        String errorCode,
        String errorMessage,
        String idempotencyKey,
        Instant completedAt) {

    public EvaluationSampleResult {
        tenantId = EvaluationRecoverySupport.tenantId(tenantId);
        runId = EvaluationRecoverySupport.text(runId, "runId", 64);
        sampleId = EvaluationRecoverySupport.text(sampleId, "sampleId", 64);
        idempotencyKey = EvaluationRecoverySupport.text(idempotencyKey, "idempotencyKey", 190);
        expectedOutput = EvaluationRecoverySupport.nullableText(expectedOutput, "expectedOutput", 65535);
        actualOutput = EvaluationRecoverySupport.nullableText(actualOutput, "actualOutput", 65535);
        scoreJson = EvaluationRecoverySupport.nullableText(scoreJson, "scoreJson", 65535);
        errorCode = EvaluationRecoverySupport.nullableText(errorCode, "errorCode", 96);
        errorMessage = EvaluationRecoverySupport.nullableText(errorMessage, "errorMessage", 65535);
        Objects.requireNonNull(status, "status");
        EvaluationRecoverySupport.instant(completedAt, "completedAt");
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        if (status == SampleResultStatus.SUCCEEDED) {
            if (errorCode != null || errorMessage != null) {
                throw new IllegalArgumentException("successful sample cannot contain an error");
            }
        } else {
            if (errorCode == null || errorMessage == null) {
                throw new IllegalArgumentException("failed sample requires errorCode and errorMessage");
            }
            if (scoreJson != null) throw new IllegalArgumentException("failed sample cannot contain a score");
        }
    }
}
