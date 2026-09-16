package com.xnlp.core.runtime;

/** Stable, provider-neutral runtime error codes. */
public enum NlpRuntimeErrorCode {
    CONFIGURATION("nlp_runtime_configuration"),
    MODEL_NOT_FOUND("nlp_runtime_model_not_found"),
    CHECKSUM_MISMATCH("nlp_runtime_checksum_mismatch"),
    MODEL_INVALID("nlp_runtime_model_invalid"),
    NOT_READY("nlp_runtime_not_ready"),
    CLOSED("nlp_runtime_closed"),
    UNSUPPORTED_CAPABILITY("nlp_runtime_unsupported_capability"),
    SATURATED("nlp_runtime_saturated"),
    EXECUTION_TIMEOUT("nlp_runtime_timeout"),
    EXECUTION_FAILED("nlp_runtime_execution_failed");

    private final String code;

    NlpRuntimeErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
