package com.xnlp.server.quota;

import com.xnlp.server.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Enforces the tenant request-per-minute and concurrent-request limits. */
@Component
public final class QuotaGuard {

    public static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(5);

    private final TenantQuotaRepository repository;
    private final Clock clock;
    private final Duration leaseTtl;

    @Autowired
    public QuotaGuard(TenantQuotaRepository repository) {
        this(repository, Clock.systemUTC(), DEFAULT_LEASE_TTL);
    }

    /** Constructor intended for configuration and deterministic tests. */
    public QuotaGuard(TenantQuotaRepository repository, Clock clock, Duration leaseTtl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseTtl = requirePositive(leaseTtl, "leaseTtl");
    }

    /**
     * Claims one request unit and one concurrency slot. Missing tenant configuration is deliberately
     * treated as unlimited so enabling the guard does not break existing installations.
     */
    public RequestLease acquire(String tenantId, String ownerId) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        String normalizedOwner = requireText(ownerId, "ownerId");
        if (repository.findLimits(normalizedTenant).isEmpty()) return RequestLease.unlimited();

        Instant now = clock.instant();
        Instant windowStart = now.truncatedTo(ChronoUnit.MINUTES);
        Instant windowEnd = windowStart.plus(1, ChronoUnit.MINUTES);
        QuotaClaim requestClaim = repository.tryClaimUsage(
                normalizedTenant, QuotaDimension.REQUEST, windowStart, windowEnd, 1);
        if (!requestClaim.granted()) {
            throw QuotaExceededException.rateLimitExceeded(secondsUntil(now, requestClaim.windowEnd()));
        }

        String leaseId = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(leaseTtl);
        ConcurrencyLease lease = repository.tryAcquireLease(
                        normalizedTenant, leaseId, normalizedOwner, now, expiresAt)
                .orElseThrow(() -> QuotaExceededException.concurrencyLimitExceeded(
                        positiveCeilingSeconds(leaseTtl)));
        return RequestLease.acquired(repository, lease.tenantId(), lease.leaseId());
    }

    /** Runs an unchecked action and guarantees release of its concurrency slot. */
    public void runGuarded(String tenantId, String ownerId, Runnable action) {
        Objects.requireNonNull(action, "action");
        try (RequestLease ignored = acquire(tenantId, ownerId)) {
            action.run();
        }
    }

    private static long secondsUntil(Instant now, Instant end) {
        Duration remaining = Duration.between(now, end);
        return Math.max(1L, positiveCeilingSeconds(remaining));
    }

    private static long positiveCeilingSeconds(Duration duration) {
        long seconds = duration.getSeconds();
        return duration.getNano() == 0 ? Math.max(1L, seconds) : Math.max(1L, seconds + 1L);
    }

    private static Duration requirePositive(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    /** Idempotent handle for a claimed concurrency slot. */
    public static final class RequestLease implements AutoCloseable {

        private static final RequestLease UNLIMITED = new RequestLease(null, null, null);

        private final TenantQuotaRepository repository;
        private final String tenantId;
        private final String leaseId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RequestLease(TenantQuotaRepository repository, String tenantId, String leaseId) {
            this.repository = repository;
            this.tenantId = tenantId;
            this.leaseId = leaseId;
        }

        private static RequestLease unlimited() {
            return UNLIMITED;
        }

        private static RequestLease acquired(TenantQuotaRepository repository, String tenantId, String leaseId) {
            return new RequestLease(repository, tenantId, leaseId);
        }

        @Override
        public void close() {
            if (repository != null && closed.compareAndSet(false, true)) {
                repository.releaseLease(tenantId, leaseId);
            }
        }
    }
}
