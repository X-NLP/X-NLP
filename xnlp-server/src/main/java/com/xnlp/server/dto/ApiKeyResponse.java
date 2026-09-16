package com.xnlp.server.dto;

import com.xnlp.server.security.ApiKeyRecord;
import com.xnlp.server.security.TenantRole;

import java.time.Instant;
import java.util.Set;

/** Public API-key metadata. Deliberately excludes the stored secret hash. */
public record ApiKeyResponse(
        String id,
        String tenantId,
        String name,
        String secretPrefix,
        Set<TenantRole> roles,
        Instant expiresAt,
        Instant revokedAt,
        String revokeReason,
        Instant lastUsedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public ApiKeyResponse {
        roles = Set.copyOf(roles);
    }

    public static ApiKeyResponse from(ApiKeyRecord record) {
        return new ApiKeyResponse(
                record.id(),
                record.tenantId(),
                record.name(),
                record.secretPrefix(),
                record.roles(),
                record.expiresAt(),
                record.revokedAt(),
                record.revokeReason(),
                record.lastUsedAt(),
                record.createdBy(),
                record.createdAt(),
                record.updatedAt());
    }
}
