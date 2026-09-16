package com.xnlp.server.dto;

import com.xnlp.core.eval.EvaluationRun;

/** Links a newly queued retry to the original evaluation lineage. */
public record EvaluationRetryResponse(
        String sourceRunId,
        String rootRunId,
        boolean failedOnly,
        int selectedSamples,
        EvaluationRun run) {
}
