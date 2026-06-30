package com.xnlp.core.errors;

import java.util.Map;

public class ConfigException extends XNLPException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Map<String, Object> detail) {
        super(message, detail);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
