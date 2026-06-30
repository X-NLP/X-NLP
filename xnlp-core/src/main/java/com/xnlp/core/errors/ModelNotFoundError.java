package com.xnlp.core.errors;

import java.util.Map;

public class ModelNotFoundError extends XNLPException {

    public ModelNotFoundError(String message) {
        super(message);
    }

    public ModelNotFoundError(String message, Map<String, Object> detail) {
        super(message, detail);
    }
}
