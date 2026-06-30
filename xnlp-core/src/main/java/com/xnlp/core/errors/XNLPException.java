package com.xnlp.core.errors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base exception for all X-NLP errors.
 *
 * <p>All framework-specific exceptions inherit from this type so callers can
 * catch a single root when needed. Carries an optional detail map for
 * structured error metadata.
 */
public class XNLPException extends RuntimeException {

    private final Map<String, Object> detail;

    public XNLPException(String message) {
        super(message);
        this.detail = Collections.emptyMap();
    }

    public XNLPException(String message, Throwable cause) {
        super(message, cause);
        this.detail = Collections.emptyMap();
    }

    public XNLPException(String message, Map<String, Object> detail) {
        super(message);
        this.detail = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public XNLPException(String message, Map<String, Object> detail, Throwable cause) {
        super(message, cause);
        this.detail = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public Map<String, Object> getDetail() {
        return detail;
    }
}
