package com.xnlp.server.config;

import com.xnlp.core.errors.*;
import com.xnlp.server.waste.WasteWorkflowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ModelNotFoundError.class)
    public ResponseEntity<Map<String, Object>> handle(ModelNotFoundError e) {
        return error(HttpStatus.NOT_FOUND, "model_not_found", e);
    }

    @ExceptionHandler(ModelLoadError.class)
    public ResponseEntity<Map<String, Object>> handle(ModelLoadError e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "model_load_error", e);
    }

    @ExceptionHandler(BackendNotSupportedError.class)
    public ResponseEntity<Map<String, Object>> handle(BackendNotSupportedError e) {
        return error(HttpStatus.BAD_REQUEST, "backend_not_supported", e);
    }

    @ExceptionHandler(ConfigException.class)
    public ResponseEntity<Map<String, Object>> handle(ConfigException e) {
        return error(HttpStatus.BAD_REQUEST, "config_error", e);
    }

    @ExceptionHandler(PredictionError.class)
    public ResponseEntity<Map<String, Object>> handle(PredictionError e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "prediction_error", e);
    }

    @ExceptionHandler(XNLPException.class)
    public ResponseEntity<Map<String, Object>> handle(XNLPException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "xnlp_error", e);
    }

    @ExceptionHandler(WasteWorkflowException.class)
    public ResponseEntity<Map<String, Object>> handle(WasteWorkflowException e) {
        return simpleError(HttpStatus.CONFLICT, "workflow_conflict", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalArgumentException e) {
        return simpleError(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handle(IllegalStateException e) {
        return simpleError(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handle(NoSuchElementException e) {
        return simpleError(HttpStatus.NOT_FOUND, "resource_not_found", e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handle(DataAccessException e) {
        log.error("Persistence operation failed", e);
        return simpleError(HttpStatus.SERVICE_UNAVAILABLE, "persistence_unavailable",
                "The configured database is unavailable");
    }

    private ResponseEntity<Map<String, Object>> simpleError(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, XNLPException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", e.getMessage());
        if (!e.getDetail().isEmpty()) {
            body.put("detail", e.getDetail());
        }
        log.error("[{}] {}", code, e.getMessage(), e);
        return ResponseEntity.status(status).body(body);
    }
}
