package com.xnlp.server.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ApiKeyRepository {
    Optional<ApiKeyRecord> findActiveByHash(String secretHash, Instant now);

    Optional<ApiKeyRecord> findById(String tenantId, String id);

    List<ApiKeyRecord> findByTenant(String tenantId, int limit, int offset);

    long countByTenant(String tenantId);

    ApiKeyRecord create(String id, String tenantId, String name, String secretPrefix, String secretHash,
                        Set<TenantRole> roles, Instant expiresAt, String createdBy, Instant now);

    void updateLastUsed(String id, Instant lastUsedAt);

    boolean scheduleRevocation(String tenantId, String id, String reason, Instant revokeAt, Instant updatedAt);

    boolean revoke(String tenantId, String id, String reason, Instant revokedAt);
}
