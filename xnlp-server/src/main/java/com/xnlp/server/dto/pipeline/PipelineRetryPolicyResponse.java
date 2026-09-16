package com.xnlp.server.dto.pipeline;

/** Effective retry policy returned for a pipeline definition. */
public record PipelineRetryPolicyResponse(int maxAttempts, long backoffMillis) {
}
