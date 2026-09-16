package com.xnlp.server.security;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

@Repository
@Profile("memory")
public class InMemoryAuditEventRepository implements AuditEventRepository {
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public AuditEvent append(String tenantId, String actor, String action, String resourceType,
                             String resourceId, String outcome, Map<String, Object> detail,
                             Instant occurredAt, Instant retainUntil) {
        AuditEvent event = new AuditEvent(UUID.randomUUID().toString(), TenantContext.normalize(tenantId), actor,
                action, resourceType, resourceId, outcome, detail, occurredAt, retainUntil);
        events.add(event); return event;
    }

    @Override
    public List<AuditEvent> find(String tenantId, String actor, String action, String resourceType,
                                 Instant from, Instant to, int limit, int offset) {
        return filtered(tenantId, actor, action, resourceType, from, to)
                .sorted(java.util.Comparator.comparing(AuditEvent::occurredAt).reversed()
                        .thenComparing(AuditEvent::id, java.util.Comparator.reverseOrder()))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public long count(String tenantId, String actor, String action, String resourceType, Instant from, Instant to) {
        return filtered(tenantId, actor, action, resourceType, from, to).count();
    }

    private Stream<AuditEvent> filtered(String tenantId, String actor, String action, String resourceType,
                                        Instant from, Instant to) {
        String tenant = TenantContext.normalize(tenantId);
        return events.stream().filter(e -> e.tenantId().equals(tenant))
                .filter(e -> actor == null || actor.isBlank() || e.actor().equals(actor))
                .filter(e -> action == null || action.isBlank() || e.action().equals(action))
                .filter(e -> resourceType == null || resourceType.isBlank() || e.resourceType().equals(resourceType))
                .filter(e -> from == null || !e.occurredAt().isBefore(from))
                .filter(e -> to == null || !e.occurredAt().isAfter(to));
    }
}
