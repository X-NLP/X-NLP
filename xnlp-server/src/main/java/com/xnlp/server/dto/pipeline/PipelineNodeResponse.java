package com.xnlp.server.dto.pipeline;

import java.util.Map;

/** Persisted node definition returned without tenant-internal fields. */
public record PipelineNodeResponse(
        String id,
        String capability,
        String name,
        Map<String, Object> parameters,
        Integer timeoutSeconds,
        PipelineRetryPolicyResponse retry) {

    public PipelineNodeResponse {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
