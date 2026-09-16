package com.xnlp.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.server.dto.ApiKeyCreateRequest;
import com.xnlp.server.dto.ApiKeyResponse;
import com.xnlp.server.dto.ApiKeyRevokeRequest;
import com.xnlp.server.dto.ApiKeyRotateRequest;
import com.xnlp.server.dto.ApiKeySecretResponse;
import com.xnlp.server.dto.PageResponse;
import com.xnlp.server.security.ApiKeyService;
import com.xnlp.server.security.AuditEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
public class SecurityController {

    private static final int MAX_PAGE = 10_000_000;
    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final ApiKeyService apiKeys;
    private final ObjectMapper objectMapper;

    public SecurityController(ApiKeyService apiKeys, ObjectMapper objectMapper) {
        this.apiKeys = apiKeys;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeySecretResponse createApiKey(@Valid @RequestBody ApiKeyCreateRequest request) {
        ApiKeyService.CreatedApiKey created = apiKeys.create(request.name(), request.roles(), request.expiresAt());
        return new ApiKeySecretResponse(ApiKeyResponse.from(created.record()), created.secret(), null);
    }

    @GetMapping("/api-keys")
    public PageResponse<ApiKeyResponse> listApiKeys(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        List<ApiKeyResponse> items = apiKeys.list(size, offset(page, size)).stream()
                .map(ApiKeyResponse::from)
                .toList();
        return PageResponse.of(items, page, size, apiKeys.count());
    }

    @PostMapping("/api-keys/{id}/rotate")
    public ApiKeySecretResponse rotateApiKey(
            @PathVariable String id,
            @Valid @RequestBody ApiKeyRotateRequest request) {
        Instant requestedAt = Instant.now();
        ApiKeyService.CreatedApiKey created = apiKeys.rotate(id, request.gracePeriodSeconds());
        return new ApiKeySecretResponse(
                ApiKeyResponse.from(created.record()),
                created.secret(),
                requestedAt.plusSeconds(request.gracePeriodSeconds()));
    }

    @DeleteMapping("/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(
            @PathVariable String id,
            @Valid @RequestBody(required = false) ApiKeyRevokeRequest request) {
        apiKeys.revoke(id, request == null ? null : request.reason());
    }

    @GetMapping("/audit-events")
    public PageResponse<AuditEvent> listAuditEvents(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        List<AuditEvent> items = apiKeys.auditEvents(
                actor, action, resourceType, from, to, size, offset(page, size));
        long total = apiKeys.auditCount(actor, action, resourceType, from, to);
        return PageResponse.of(items, page, size, total);
    }

    @GetMapping(value = "/audit-events/export", produces = "application/x-ndjson")
    public ResponseEntity<String> exportAuditEvents(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        List<AuditEvent> events = apiKeys.auditEvents(
                actor, action, resourceType, from, to, MAX_EXPORT_ROWS, 0);
        StringBuilder body = new StringBuilder();
        for (AuditEvent event : events) {
            body.append(toJson(event)).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header("Content-Disposition", "attachment; filename=security-audit.ndjson")
                .body(body.toString());
    }

    private String toJson(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit event could not be exported", exception);
        }
    }

    private static int offset(int page, int size) {
        return Math.multiplyExact(page, size);
    }
}
