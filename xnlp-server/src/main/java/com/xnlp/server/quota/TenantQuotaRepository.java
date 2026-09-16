package com.xnlp.server.quota;

import java.time.Instant;
import java.util.Optional;

public interface TenantQuotaRepository {

    /** Returns the tenant limits. An absent configuration behaves as zero allowance. */
    Optional<TenantQuota> findLimits(String tenantId);

    /** Creates or replaces all tenant limits; zero disables the corresponding dimension. */
    TenantQuota saveLimits(String tenantId, QuotaLimits limits);

    Optional<QuotaUsageWindow> findUsageWindow(
            String tenantId, QuotaDimension dimension, Instant windowStart);

    /**
     * Atomically consumes units from the caller-defined fixed window. A rejected claim never increments usage.
     * The same tenant, dimension and window start must always use the same window end.
     */
    QuotaClaim tryClaimUsage(
            String tenantId,
            QuotaDimension dimension,
            Instant windowStart,
            Instant windowEnd,
            long units);

    Optional<ConcurrencyLease> findLease(String tenantId, String leaseId);

    /**
     * Atomically occupies one slot below the tenant concurrency limit. Repeating an active lease ID for its
     * original owner is idempotent; a different owner cannot take it until expiry or explicit release.
     */
    Optional<ConcurrencyLease> tryAcquireLease(
            String tenantId,
            String leaseId,
            String ownerId,
            Instant acquiredAt,
            Instant expiresAt);

    boolean releaseLease(String tenantId, String leaseId);

    /** Deletes leases expiring at or before the supplied instant across all tenants. */
    int deleteExpiredLeases(Instant expiredAtOrBefore);

    long countActiveLeases(String tenantId, Instant at);
}
