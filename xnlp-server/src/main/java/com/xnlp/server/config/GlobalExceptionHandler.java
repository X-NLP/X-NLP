package com.xnlp.server.config;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.xnlp.core.errors.*;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.runtime.NlpRuntimeErrorCode;
import com.xnlp.core.runtime.NlpRuntimeException;
import com.xnlp.server.dto.ApiErrorResponse;
import com.xnlp.server.security.TenantMembershipException;
import com.xnlp.server.security.TenantRole;
import com.xnlp.server.waste.WasteWorkflowException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final ObjectProvider<Tracer> tracers;

    public GlobalExceptionHandler(ObjectProvider<Tracer> tracers) {
        this.tracers = tracers;
    }

    @ExceptionHandler(ModelNotFoundError.class)
    public ResponseEntity<ApiErrorResponse> handle(ModelNotFoundError e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "model_not_found", e, request);
    }

    @ExceptionHandler(ModelLoadError.class)
    public ResponseEntity<ApiErrorResponse> handle(ModelLoadError e, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "model_load_error", e, request);
    }

    @ExceptionHandler(BackendNotSupportedError.class)
    public ResponseEntity<ApiErrorResponse> handle(BackendNotSupportedError e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "backend_not_supported", e, request);
    }

    @ExceptionHandler(ConfigException.class)
    public ResponseEntity<ApiErrorResponse> handle(ConfigException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "config_error", e, request);
    }

    @ExceptionHandler(PredictionError.class)
    public ResponseEntity<ApiErrorResponse> handle(PredictionError e, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "prediction_error", e, request);
    }

    @ExceptionHandler(RagContractException.class)
    public ResponseEntity<ApiErrorResponse> handle(RagContractException e, HttpServletRequest request) {
        return error(statusFor(e.getErrorCode().kind()), e.getErrorCode().code(), e, request);
    }

    @ExceptionHandler(NlpRuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handle(NlpRuntimeException e, HttpServletRequest request) {
        return error(statusFor(e.getErrorCode()), e.getErrorCode().code(), e, request);
    }

    @ExceptionHandler(XNLPException.class)
    public ResponseEntity<ApiErrorResponse> handle(XNLPException e, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "xnlp_error", e, request);
    }

    @ExceptionHandler(WasteWorkflowException.class)
    public ResponseEntity<ApiErrorResponse> handle(WasteWorkflowException e, HttpServletRequest request) {
        return simpleError(HttpStatus.CONFLICT, "workflow_conflict", e.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handle(AccessDeniedException e, HttpServletRequest request) {
        return simpleError(HttpStatus.FORBIDDEN, "forbidden",
                "The authenticated principal is not permitted to perform this operation", request);
    }

    @ExceptionHandler(TenantMembershipException.class)
    public ResponseEntity<ApiErrorResponse> handle(TenantMembershipException e, HttpServletRequest request) {
        return switch (e.reason()) {
            case MEMBERSHIP_NOT_FOUND -> simpleError(
                    HttpStatus.NOT_FOUND, "membership_not_found", e.getMessage(), request);
            case LAST_ADMIN_REQUIRED -> simpleError(
                    HttpStatus.CONFLICT, "last_admin_required", e.getMessage(), request);
            case ROLE_INVALID -> simpleError(
                    HttpStatus.BAD_REQUEST, "role_invalid", e.getMessage(), request);
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handle(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiErrorResponse.FieldViolation(
                        fieldError.getField(), defaultMessage(fieldError.getDefaultMessage())))
                .toList();
        return simpleError(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed",
                violations, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handle(ConstraintViolationException e, HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> violations = e.getConstraintViolations().stream()
                .map(this::toViolation)
                .toList();
        return simpleError(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed",
                violations, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handle(HandlerMethodValidationException e, HttpServletRequest request) {
        List<ApiErrorResponse.FieldViolation> violations = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiErrorResponse.FieldViolation(
                                parameterName(result), defaultMessage(error.getDefaultMessage()))))
                .toList();
        return simpleError(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed",
                violations, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handle(HttpMessageNotReadableException e, HttpServletRequest request) {
        if (hasInvalidTenantRole(e)) {
            return simpleError(HttpStatus.BAD_REQUEST, "role_invalid",
                    "Tenant membership contains an unsupported role", request);
        }
        return simpleError(HttpStatus.BAD_REQUEST, "malformed_request", "Request body is malformed", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handle(IllegalArgumentException e, HttpServletRequest request) {
        return simpleError(HttpStatus.BAD_REQUEST, "invalid_request", safeMessage(e), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handle(IllegalStateException e, HttpServletRequest request) {
        String code = safeMessage(e).toLowerCase().contains("provider")
                ? "provider_unavailable" : "service_unavailable";
        return simpleError(HttpStatus.SERVICE_UNAVAILABLE, code, safeMessage(e), request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handle(NoSuchElementException e, HttpServletRequest request) {
        return simpleError(HttpStatus.NOT_FOUND, "resource_not_found", safeMessage(e), request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handle(DataAccessException e, HttpServletRequest request) {
        log.error("Persistence operation failed", e);
        return simpleError(HttpStatus.SERVICE_UNAVAILABLE, "persistence_unavailable",
                "The configured database is unavailable", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled REST request failure", e);
        return simpleError(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "The server could not complete the request", request);
    }

    private ResponseEntity<ApiErrorResponse> simpleError(HttpStatus status, String code, String message,
                                                          HttpServletRequest request) {
        return simpleError(status, code, message, null, request);
    }

    private ResponseEntity<ApiErrorResponse> simpleError(HttpStatus status, String code, String message,
                                                          List<ApiErrorResponse.FieldViolation> violations,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(), status.value(), code, message, null, violations,
                requestId(request), traceId()));
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, XNLPException e,
                                                    HttpServletRequest request) {
        Map<String, Object> detail = e.getDetail().isEmpty() ? null : new LinkedHashMap<>(e.getDetail());
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(), status.value(), code, safeMessage(e), detail, null,
                requestId(request), traceId());
        log.error("[{}] {}", code, e.getMessage(), e);
        return ResponseEntity.status(status).body(body);
    }

    private String requestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private String traceId() {
        Tracer tracer = tracers.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    private ApiErrorResponse.FieldViolation toViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "request" : violation.getPropertyPath().toString();
        return new ApiErrorResponse.FieldViolation(path, defaultMessage(violation.getMessage()));
    }

    private static String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name == null || name.isBlank()
                ? "parameter[" + result.getMethodParameter().getParameterIndex() + "]"
                : name;
    }

    private static String defaultMessage(String message) {
        return message == null || message.isBlank() ? "Invalid value" : message;
    }

    private static HttpStatus statusFor(RagErrorCode.Kind kind) {
        return switch (kind) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
        };
    }

    private static HttpStatus statusFor(NlpRuntimeErrorCode code) {
        return switch (code) {
            case UNSUPPORTED_CAPABILITY -> HttpStatus.BAD_REQUEST;
            case EXECUTION_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
            case CONFIGURATION, MODEL_NOT_FOUND, CHECKSUM_MISMATCH, MODEL_INVALID,
                    NOT_READY, CLOSED, SATURATED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static boolean hasInvalidTenantRole(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InvalidFormatException invalidFormat
                    && (invalidFormat.getTargetType() == TenantRole.class
                    || invalidFormat.getPath().stream().anyMatch(
                    reference -> "roles".equals(reference.getFieldName())))) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains(TenantRole.class.getName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeMessage(Exception e) {
        return defaultMessage(e.getMessage());
    }
}
