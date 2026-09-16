package com.xnlp.server.quota;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaGuardTest {

    private static final Instant NOW = Instant.parse("2026-09-16T08:15:30.250Z");
    private static final Duration LEASE_TTL = Duration.ofSeconds(45);

    @Test
    void acquire_MissingConfiguration_RemainsUnlimited() {
        InMemoryTenantQuotaRepository repository = new InMemoryTenantQuotaRepository();
        QuotaGuard guard = guard(repository);

        guard.runGuarded("tenant-a", "request-1", () -> { });

        assertThat(repository.findUsageWindow(
                "tenant-a", QuotaDimension.REQUEST, NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)))
                .isEmpty();
        assertThat(repository.countActiveLeases("tenant-a", NOW)).isZero();
    }

    @Test
    void acquire_RequestLimitReached_ReportsWindowRetryDelay() {
        InMemoryTenantQuotaRepository repository = configuredRepository(1, 2);
        QuotaGuard guard = guard(repository);

        guard.runGuarded("tenant-a", "request-1", () -> { });

        assertThatThrownBy(() -> guard.acquire("tenant-a", "request-2"))
                .isInstanceOfSatisfying(QuotaExceededException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(QuotaExceededException.RATE_LIMIT_EXCEEDED);
                    assertThat(exception.retryAfterSeconds()).isEqualTo(30);
                });
    }

    @Test
    void acquire_ConcurrencyLimitReached_ReleasesCapacityOnClose() {
        InMemoryTenantQuotaRepository repository = configuredRepository(10, 1);
        QuotaGuard guard = guard(repository);
        QuotaGuard.RequestLease first = guard.acquire("tenant-a", "request-1");

        assertThatThrownBy(() -> guard.acquire("tenant-a", "request-2"))
                .isInstanceOfSatisfying(QuotaExceededException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(QuotaExceededException.CONCURRENCY_LIMIT_EXCEEDED);
                    assertThat(exception.retryAfterSeconds()).isEqualTo(45);
                });
        assertThat(repository.countActiveLeases("tenant-a", NOW)).isOne();

        first.close();
        try (QuotaGuard.RequestLease ignored = guard.acquire("tenant-a", "request-3")) {
            assertThat(repository.countActiveLeases("tenant-a", NOW)).isOne();
        }
        assertThat(repository.countActiveLeases("tenant-a", NOW)).isZero();
    }

    @Test
    void runGuarded_ActionFails_ReleasesConcurrencyLeaseInFinally() {
        InMemoryTenantQuotaRepository repository = configuredRepository(10, 1);
        QuotaGuard guard = guard(repository);

        assertThatThrownBy(() -> guard.runGuarded("tenant-a", "request-1", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(repository.countActiveLeases("tenant-a", NOW)).isZero();
    }

    private static QuotaGuard guard(InMemoryTenantQuotaRepository repository) {
        return new QuotaGuard(repository, Clock.fixed(NOW, ZoneOffset.UTC), LEASE_TTL);
    }

    private static InMemoryTenantQuotaRepository configuredRepository(long requestsPerMinute,
                                                                       int concurrentRequests) {
        InMemoryTenantQuotaRepository repository = new InMemoryTenantQuotaRepository();
        repository.saveLimits("tenant-a", new QuotaLimits(requestsPerMinute, 0, 0, concurrentRequests));
        return repository;
    }
}
