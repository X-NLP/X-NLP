package com.xnlp.server.security;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String id,
        String tenantId,
        String actor,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        Map<String, Object> detail,
        Instant occurredAt,
        Instant retainUntil) {

    public AuditEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
