package com.xnlp.server.evaluation.recovery;

import java.time.Instant;

public record EvaluationCheckpoint(
        String tenantId,
        String runId,
        int nextSampleSequence,
        int processedSamples,
        int succeededSamples,
        int failedSamples,
        String lastSampleId,
        long fencingToken,
        Instant updatedAt) {

    public EvaluationCheckpoint {
        tenantId = EvaluationRecoverySupport.tenantId(tenantId);
        runId = EvaluationRecoverySupport.text(runId, "runId", 64);
        lastSampleId = EvaluationRecoverySupport.nullableText(lastSampleId, "lastSampleId", 64);
        EvaluationRecoverySupport.instant(updatedAt, "updatedAt");
        if (nextSampleSequence < 0) throw new IllegalArgumentException("nextSampleSequence must not be negative");
        if (processedSamples < 0 || succeededSamples < 0 || failedSamples < 0) {
            throw new IllegalArgumentException("sample counters must not be negative");
        }
        if (processedSamples != succeededSamples + failedSamples) {
            throw new IllegalArgumentException("processedSamples must equal succeededSamples + failedSamples");
        }
        if (processedSamples == 0 && lastSampleId != null) {
            throw new IllegalArgumentException("empty checkpoint cannot have lastSampleId");
        }
        if (processedSamples > 0 && lastSampleId == null) {
            throw new IllegalArgumentException("non-empty checkpoint requires lastSampleId");
        }
        if (fencingToken < 0) throw new IllegalArgumentException("fencingToken must not be negative");
    }
}
