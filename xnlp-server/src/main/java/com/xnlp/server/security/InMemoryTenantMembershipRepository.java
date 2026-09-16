package com.xnlp.server.security;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class InMemoryTenantMembershipRepository implements TenantMembershipRepository {

    private final Map<String, TenantMembership> memberships = new ConcurrentHashMap<>();

    @Override
    public Optional<TenantMembership> find(String tenantId, String subject) {
        return Optional.ofNullable(memberships.get(key(tenantId, subject)));
    }

    @Override
    public List<TenantMembership> findByTenant(String tenantId, int limit, int offset) {
        String normalized = TenantContext.normalize(tenantId);
        return memberships.values().stream()
                .filter(membership -> membership.tenantId().equals(normalized))
                .sorted(Comparator.comparing(TenantMembership::subject))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public TenantMembership save(String tenantId, String subject, Set<TenantRole> roles) {
        String key = key(tenantId, subject);
        Instant now = Instant.now();
        return memberships.compute(key, (ignored, existing) -> new TenantMembership(
                TenantContext.normalize(tenantId), subject.trim(), roles,
                existing == null ? now : existing.createdAt(), now));
    }

    @Override
    public boolean delete(String tenantId, String subject) {
        return memberships.remove(key(tenantId, subject)) != null;
    }

    @Override
    public long countByRole(String tenantId, TenantRole role) {
        return findByTenant(tenantId, Integer.MAX_VALUE, 0).stream()
                .filter(membership -> membership.roles().contains(role)).count();
    }

    private static String key(String tenantId, String subject) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        return TenantContext.normalize(tenantId) + '\u0000' + subject.trim();
    }
}
