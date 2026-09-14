package com.xnlp.client;

/**
 * Exception raised when the X-NLP server cannot complete an SDK request.
 */
public final class XNLPClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public XNLPClientException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public XNLPClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public boolean isHttpError() {
        return statusCode > 0;
    }
}
