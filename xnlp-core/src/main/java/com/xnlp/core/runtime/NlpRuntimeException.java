package com.xnlp.core.runtime;

import com.xnlp.core.errors.XNLPException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral runtime failure whose public message and details are safe to expose. */
public final class NlpRuntimeException extends XNLPException {

    private final NlpRuntimeErrorCode errorCode;

    public NlpRuntimeException(NlpRuntimeErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public NlpRuntimeException(NlpRuntimeErrorCode errorCode, String message,
                               Map<String, Object> detail) {
        super(requireMessage(message), safeDetail(errorCode, detail));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public NlpRuntimeErrorCode getErrorCode() {
        return errorCode;
    }

    private static Map<String, Object> safeDetail(NlpRuntimeErrorCode errorCode,
                                                   Map<String, Object> detail) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Map<String, Object> result = new LinkedHashMap<>();
        if (detail != null) {
            result.putAll(detail);
        }
        result.put("runtimeError", errorCode.code());
        return result;
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }
}
