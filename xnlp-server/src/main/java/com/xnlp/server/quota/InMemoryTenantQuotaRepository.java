package com.xnlp.server.quota;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
@Profile("memory")
public class InMemoryTenantQuotaRepository implements TenantQuotaRepository {

    private final Map<String, TenantQuota> quotas = new HashMap<>();
    private final Map<WindowKey, QuotaUsageWindow> windows = new HashMap<>();
    private final Map<LeaseKey, ConcurrencyLease> leases = new HashMap<>();

    @Override
    public synchronized Optional<TenantQuota> findLimits(String tenantId) {
        return Optional.ofNullable(quotas.get(TenantContext.normalize(tenantId)));
    }

    @Override
    public synchronized TenantQuota saveLimits(String tenantId, QuotaLimits limits) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        Objects.requireNonNull(limits, "limits");
        Instant now = Instant.now();
        TenantQuota quota = new TenantQuota(normalizedTenant, limits,
                Optional.ofNullable(quotas.get(normalizedTenant)).map(TenantQuota::createdAt).orElse(now), now);
        quotas.put(normalizedTenant, quota);
        return quota;
    }

    @Override
    public synchronized Optional<QuotaUsageWindow> findUsageWindow(
            String tenantId, QuotaDimension dimension, Instant windowStart) {
        return Optional.ofNullable(windows.get(new WindowKey(
                TenantContext.normalize(tenantId), Objects.requireNonNull(dimension, "dimension"),
                Objects.requireNonNull(windowStart, "windowStart"))));
    }

    @Override
    public synchronized QuotaClaim tryClaimUsage(
            String tenantId,
            QuotaDimension dimension,
            Instant windowStart,
            Instant windowEnd,
            long units) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowEnd.isAfter(windowStart)) throw new IllegalArgumentException("windowEnd must be after windowStart");
        if (units < 1) throw new IllegalArgumentException("units must be positive");
        long limit = Optional.ofNullable(quotas.get(normalizedTenant))
                .map(quota -> quota.limits().limitFor(dimension)).orElse(0L);
        WindowKey key = new WindowKey(normalizedTenant, dimension, windowStart);
        QuotaUsageWindow existing = windows.get(key);
        if (existing != null && !existing.windowEnd().equals(windowEnd)) {
            throw new IllegalArgumentException("A quota window start cannot be reused with a different end");
        }
        long used = existing == null ? 0L : existing.usedUnits();
        if (units > limit || used > limit - units) return new QuotaClaim(false, used, limit, windowEnd);
        long claimed = used + units;
        windows.put(key, new QuotaUsageWindow(normalizedTenant, dimension, windowStart, windowEnd,
                claimed, limit, Instant.now()));
        return new QuotaClaim(true, claimed, limit, windowEnd);
    }

    @Override
    public synchronized Optional<ConcurrencyLease> findLease(String tenantId, String leaseId) {
        return Optional.ofNullable(leases.get(new LeaseKey(
                TenantContext.normalize(tenantId), normalizeLeaseId(leaseId))));
    }

    @Override
    public synchronized Optional<ConcurrencyLease> tryAcquireLease(
            String tenantId,
            String leaseId,
            String ownerId,
            Instant acquiredAt,
            Instant expiresAt) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        String normalizedLease = normalizeLeaseId(leaseId);
        String normalizedOwner = ConcurrencyLease.requireText(ownerId, "ownerId");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(acquiredAt)) throw new IllegalArgumentException("expiresAt must be after acquiredAt");
        int limit = Optional.ofNullable(quotas.get(normalizedTenant))
                .map(quota -> quota.limits().concurrentRequests()).orElse(0);
        LeaseKey key = new LeaseKey(normalizedTenant, normalizedLease);
        ConcurrencyLease existing = leases.get(key);
        if (existing != null && existing.activeAt(acquiredAt)) {
            return existing.ownerId().equals(normalizedOwner) ? Optional.of(existing) : Optional.empty();
        }
        leases.entrySet().removeIf(entry -> entry.getKey().tenantId().equals(normalizedTenant)
                && !entry.getValue().activeAt(acquiredAt));
        boolean[] occupied = new boolean[limit];
        leases.values().stream().filter(lease -> lease.tenantId().equals(normalizedTenant))
                .filter(lease -> lease.slot() < limit).forEach(lease -> occupied[lease.slot()] = true);
        for (int slot = 0; slot < limit; slot++) {
            if (!occupied[slot]) {
                ConcurrencyLease acquired = new ConcurrencyLease(normalizedTenant, normalizedLease, slot,
                        normalizedOwner, acquiredAt, expiresAt);
                leases.put(key, acquired);
                return Optional.of(acquired);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized boolean releaseLease(String tenantId, String leaseId) {
        return leases.remove(new LeaseKey(TenantContext.normalize(tenantId), normalizeLeaseId(leaseId))) != null;
    }

    @Override
    public synchronized int deleteExpiredLeases(Instant expiredAtOrBefore) {
        Objects.requireNonNull(expiredAtOrBefore, "expiredAtOrBefore");
        int before = leases.size();
        leases.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(expiredAtOrBefore));
        return before - leases.size();
    }

    @Override
    public synchronized long countActiveLeases(String tenantId, Instant at) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        Objects.requireNonNull(at, "at");
        return leases.values().stream().filter(lease -> lease.tenantId().equals(normalizedTenant))
                .filter(lease -> lease.activeAt(at)).count();
    }

    private static String normalizeLeaseId(String leaseId) {
        String value = ConcurrencyLease.requireText(leaseId, "leaseId");
        if (value.length() > 96) throw new IllegalArgumentException("leaseId must not exceed 96 characters");
        return value;
    }

    private record WindowKey(String tenantId, QuotaDimension dimension, Instant windowStart) {
    }

    private record LeaseKey(String tenantId, String leaseId) {
    }
}
