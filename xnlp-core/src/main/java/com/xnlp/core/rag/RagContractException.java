package com.xnlp.core.rag;

import com.xnlp.core.errors.XNLPException;

import java.util.Map;
import java.util.Objects;

/** Exception carrying a stable RAG error code without coupling core to HTTP. */
public class RagContractException extends XNLPException {

    private final RagErrorCode errorCode;

    public RagContractException(RagErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public RagContractException(RagErrorCode errorCode, String message, Map<String, Object> detail) {
        super(message, detail);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public RagErrorCode getErrorCode() {
        return errorCode;
    }
}
