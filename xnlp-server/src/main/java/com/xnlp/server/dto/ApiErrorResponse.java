package com.xnlp.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Stable error contract shared by REST clients, the web UI and CLI. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, Object> detail,
        List<FieldViolation> violations,
        String requestId,
        String traceId) {

    public record FieldViolation(String field, String message) {
    }
}
