package com.xnlp.core.errors;

import java.util.Map;

public class PredictionError extends XNLPException {

    public PredictionError(String message) {
        super(message);
    }

    public PredictionError(String message, Throwable cause) {
        super(message, cause);
    }

    public PredictionError(String message, Map<String, Object> detail) {
        super(message, detail);
    }
}
