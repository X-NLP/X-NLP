package com.xnlp.server.pipeline;

import java.util.Map;

/** Stable public errors for persistent pipeline operations. */
public final class PipelineDagException extends RuntimeException {
    public enum Reason {
        PIPELINE_NOT_FOUND, PIPELINE_VERSION_CONFLICT, PIPELINE_RUN_NOT_FOUND,
        PIPELINE_RUN_TERMINAL, PIPELINE_QUOTA_EXCEEDED, TRACE_NOT_READY, PIPELINE_EXECUTION_FAILED
    }

    private final Reason reason;
    private final Map<String, Object> detail;

    private PipelineDagException(Reason reason, String message, Map<String, Object> detail) {
        super(message);
        this.reason = reason;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public static PipelineDagException pipelineNotFound() {
        return new PipelineDagException(Reason.PIPELINE_NOT_FOUND, "Pipeline was not found", Map.of());
    }

    public static PipelineDagException versionConflict(long currentVersion) {
        return new PipelineDagException(Reason.PIPELINE_VERSION_CONFLICT,
                "Pipeline version does not match", Map.of("currentVersion", currentVersion));
    }

    public static PipelineDagException runNotFound() {
        return new PipelineDagException(Reason.PIPELINE_RUN_NOT_FOUND, "Pipeline run was not found", Map.of());
    }

    public static PipelineDagException runTerminal() {
        return new PipelineDagException(Reason.PIPELINE_RUN_TERMINAL,
                "Pipeline run is already terminal", Map.of());
    }

    public static PipelineDagException traceNotReady() {
        return new PipelineDagException(Reason.TRACE_NOT_READY,
                "Pipeline trace is available after the run is terminal", Map.of());
    }

    public Reason reason() { return reason; }
    public Map<String, Object> detail() { return detail; }
}
