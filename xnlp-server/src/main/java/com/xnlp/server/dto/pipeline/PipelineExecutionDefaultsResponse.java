package com.xnlp.server.dto.pipeline;

/** Effective execution defaults stored with a pipeline version. */
public record PipelineExecutionDefaultsResponse(
        int timeoutSeconds,
        PipelineRetryPolicyResponse retry,
        int maxParallelism) {
}
