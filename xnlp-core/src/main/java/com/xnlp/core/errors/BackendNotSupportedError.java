package com.xnlp.core.errors;

import java.util.Map;

public class BackendNotSupportedError extends XNLPException {

    public BackendNotSupportedError(String message) {
        super(message);
    }

    public BackendNotSupportedError(String message, Map<String, Object> detail) {
        super(message, detail);
    }
}
