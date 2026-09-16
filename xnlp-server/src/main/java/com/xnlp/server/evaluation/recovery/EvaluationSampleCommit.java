package com.xnlp.server.evaluation.recovery;

import java.util.Objects;

/** Atomic outcome of persisting one sample and advancing its checkpoint. */
public record EvaluationSampleCommit(
        EvaluationSampleResult result,
        EvaluationCheckpoint checkpoint) {

    public EvaluationSampleCommit {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!result.tenantId().equals(checkpoint.tenantId())
                || !result.runId().equals(checkpoint.runId())) {
            throw new IllegalArgumentException("Sample and checkpoint must belong to the same run");
        }
    }
}
