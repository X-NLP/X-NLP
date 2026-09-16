package com.xnlp.server.pipeline.persistence;

import java.util.Objects;

public record PipelineWriteResult(PipelineWriteStatus status, PipelineDefinition definition) {
    public PipelineWriteResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(definition, "definition");
    }
}
