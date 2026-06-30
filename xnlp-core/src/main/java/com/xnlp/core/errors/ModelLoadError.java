package com.xnlp.core.errors;

import java.util.Map;

public class ModelLoadError extends XNLPException {

    public ModelLoadError(String message) {
        super(message);
    }

    public ModelLoadError(String message, Throwable cause) {
        super(message, cause);
    }

    public ModelLoadError(String message, Map<String, Object> detail) {
        super(message, detail);
    }
}
