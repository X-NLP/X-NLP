package com.xnlp.server.pipeline.persistence;

import java.util.List;

/** Tenant-scoped page of pipeline runs. */
public record PipelineRunPage(List<PipelineRun> items, long total) {

    public PipelineRunPage {
        items = items == null ? List.of() : List.copyOf(items);
        if (total < 0) throw new IllegalArgumentException("total must not be negative");
    }
}
