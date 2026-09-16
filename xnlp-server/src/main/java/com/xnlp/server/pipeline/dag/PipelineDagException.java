package com.xnlp.server.pipeline.dag;

/** Stable domain error raised while defining or planning a pipeline DAG. */
public final class PipelineDagException extends RuntimeException {

    public static final String PIPELINE_CYCLE = "pipeline_cycle";
    public static final String PIPELINE_INVALID = "pipeline_invalid";

    private final String code;

    private PipelineDagException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static PipelineDagException cycle(String message) {
        return new PipelineDagException(PIPELINE_CYCLE, message);
    }

    public static PipelineDagException invalid(String message) {
        return new PipelineDagException(PIPELINE_INVALID, message);
    }

    public String code() {
        return code;
    }
}
