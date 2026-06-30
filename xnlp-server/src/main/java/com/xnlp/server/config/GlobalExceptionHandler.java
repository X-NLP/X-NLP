package com.xnlp.server.config;

import com.xnlp.core.errors.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
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
