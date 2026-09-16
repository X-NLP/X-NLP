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
public class InMemoryApiKeyRepository implements ApiKeyRepository {

    private final Map<String, ApiKeyRecord> keys = new ConcurrentHashMap<>();

    @Override
    public Optional<ApiKeyRecord> findActiveByHash(String secretHash, Instant now) {
        return keys.values().stream().filter(key -> key.secretHash().equals(secretHash)
                && (key.revokedAt() == null || key.revokedAt().isAfter(now))
                && (key.expiresAt() == null || key.expiresAt().isAfter(now))).findFirst();
    }

    @Override
    public Optional<ApiKeyRecord> findById(String tenantId, String id) {
        return Optional.ofNullable(keys.get(id)).filter(key -> key.tenantId().equals(TenantContext.normalize(tenantId)));
    }

    @Override
    public List<ApiKeyRecord> findByTenant(String tenantId, int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0) throw new IllegalArgumentException("Invalid API key pagination");
        String normalized = TenantContext.normalize(tenantId);
        return keys.values().stream().filter(key -> key.tenantId().equals(normalized))
                .sorted(Comparator.comparing(ApiKeyRecord::createdAt).reversed().thenComparing(ApiKeyRecord::id))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public long countByTenant(String tenantId) {
        String normalized = TenantContext.normalize(tenantId);
        return keys.values().stream().filter(key -> key.tenantId().equals(normalized)).count();
    }

    @Override
    public ApiKeyRecord create(String id, String tenantId, String name, String secretPrefix, String secretHash,
                               Set<TenantRole> roles, Instant expiresAt, String createdBy, Instant now) {
        ApiKeyRecord record = new ApiKeyRecord(id, TenantContext.normalize(tenantId), name, secretPrefix,
                secretHash, roles, expiresAt, null, null, null, createdBy, now, now);
        if (keys.putIfAbsent(id, record) != null) throw new IllegalStateException("Duplicate API key id");
        return record;
    }

    @Override
    public void updateLastUsed(String id, Instant lastUsedAt) {
        keys.computeIfPresent(id, (ignored, key) -> new ApiKeyRecord(key.id(), key.tenantId(), key.name(),
                key.secretPrefix(), key.secretHash(), key.roles(), key.expiresAt(), key.revokedAt(),
                key.revokeReason(), lastUsedAt, key.createdBy(), key.createdAt(), lastUsedAt));
    }

    @Override
    public boolean scheduleRevocation(String tenantId, String id, String reason, Instant revokeAt, Instant updatedAt) {
        ApiKeyRecord existing = findById(tenantId, id).orElse(null);
        if (existing == null || (existing.revokedAt() != null && !existing.revokedAt().isAfter(revokeAt))) return false;
        keys.put(id, new ApiKeyRecord(existing.id(), existing.tenantId(), existing.name(), existing.secretPrefix(),
                existing.secretHash(), existing.roles(), existing.expiresAt(), revokeAt, reason,
                existing.lastUsedAt(), existing.createdBy(), existing.createdAt(), updatedAt));
        return true;
    }

    @Override
    public boolean revoke(String tenantId, String id, String reason, Instant revokedAt) {
        ApiKeyRecord existing = findById(tenantId, id).orElse(null);
        if (existing == null || existing.revokedAt() != null) return false;
        keys.put(id, new ApiKeyRecord(existing.id(), existing.tenantId(), existing.name(), existing.secretPrefix(),
                existing.secretHash(), existing.roles(), existing.expiresAt(), revokedAt, reason,
                existing.lastUsedAt(), existing.createdBy(), existing.createdAt(), revokedAt));
        return true;
    }
}
