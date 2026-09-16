package com.xnlp.server.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record ApiKeyRecord(
        String id,
        String tenantId,
        String name,
        String secretPrefix,
        String secretHash,
        Set<TenantRole> roles,
        Instant expiresAt,
        Instant revokedAt,
        String revokeReason,
        Instant lastUsedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public ApiKeyRecord {
        id = requireText(id, "id");
        tenantId = requireText(tenantId, "tenantId");
        name = requireText(name, "name");
        secretPrefix = requireText(secretPrefix, "secretPrefix");
        secretHash = requireText(secretHash, "secretHash");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.isEmpty()) throw new IllegalArgumentException("roles must not be empty");
        createdBy = requireText(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean activeAt(Instant instant) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(instant));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
