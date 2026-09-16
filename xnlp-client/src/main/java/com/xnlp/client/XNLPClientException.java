package com.xnlp.client;

/**
 * Exception raised when the X-NLP server cannot complete an SDK request.
 */
public final class XNLPClientException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final ApiErrorResponse apiError;

    public XNLPClientException(String message, int statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }

    public XNLPClientException(String message, int statusCode, String responseBody,
                               ApiErrorResponse apiError) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.apiError = apiError;
    }

    public XNLPClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
        this.apiError = null;
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

    /** Parsed server error when the response followed the stable error contract. */
    public ApiErrorResponse getApiError() {
        return apiError;
    }

    public String getErrorCode() {
        return apiError == null ? null : apiError.error();
    }

    public String getRequestId() {
        return apiError == null ? null : apiError.requestId();
    }

    public String getTraceId() {
        return apiError == null ? null : apiError.traceId();
    }
}
