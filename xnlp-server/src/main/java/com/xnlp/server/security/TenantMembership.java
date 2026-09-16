package com.xnlp.server.security;

import com.xnlp.server.tenant.TenantContext;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record TenantMembership(
        String tenantId,
        String subject,
        Set<TenantRole> roles,
        Instant createdAt,
        Instant updatedAt) {

    public TenantMembership {
        tenantId = TenantContext.normalize(tenantId);
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        subject = subject.trim();
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
