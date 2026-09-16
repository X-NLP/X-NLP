package com.xnlp.server.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface AuditEventRepository {
    AuditEvent append(String tenantId, String actor, String action, String resourceType,
                      String resourceId, String outcome, Map<String, Object> detail,
                      Instant occurredAt, Instant retainUntil);

    List<AuditEvent> find(String tenantId, String actor, String action, String resourceType,
                          Instant from, Instant to, int limit, int offset);

    long count(String tenantId, String actor, String action, String resourceType, Instant from, Instant to);
}
