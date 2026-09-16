package com.xnlp.server.quota;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Portable JDBC tenant quota repository")
class JdbcTenantQuotaRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcTenantQuotaRepository quotas;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = initializedDataSource("MySQL");
        jdbc = new JdbcTemplate(dataSource);
        quotas = new JdbcTenantQuotaRepository(jdbc);
    }

    @Test
    void migrationSql_LoadsInPostgreSqlCompatibilityMode() {
        JdbcTemplate postgresql = new JdbcTemplate(initializedDataSource("PostgreSQL"));

        assertThat(postgresql.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN "
                        + "('TENANT_QUOTAS', 'QUOTA_USAGE_WINDOWS', 'QUOTA_CONCURRENCY_LEASES')",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void saveLimits_IsTenantScopedAndPreservesCreationTime() {
        TenantQuota first = quotas.saveLimits("tenant-a", new QuotaLimits(10, 20, 30, 2));
        TenantQuota second = quotas.saveLimits("tenant-a", new QuotaLimits(11, 21, 31, 3));
        quotas.saveLimits("tenant-b", new QuotaLimits(1, 2, 3, 1));

        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.limits()).isEqualTo(new QuotaLimits(11, 21, 31, 3));
        assertThat(quotas.findLimits("tenant-b")).get().extracting(TenantQuota::limits)
                .isEqualTo(new QuotaLimits(1, 2, 3, 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isEqualTo(2);
    }

    @Test
    void tryClaimUsage_AtomicallyEnforcesSharedFixedWindowLimit() throws Exception {
        quotas.saveLimits("tenant-a", new QuotaLimits(10, 5, 2, 1));
        JdbcTenantQuotaRepository secondInstance = new JdbcTenantQuotaRepository(jdbc);
        Instant start = Instant.parse("2026-09-16T00:00:00Z");
        Instant end = start.plus(1, ChronoUnit.MINUTES);

        List<QuotaClaim> claims = runConcurrently(24, index -> (index % 2 == 0 ? quotas : secondInstance)
                .tryClaimUsage("tenant-a", QuotaDimension.REQUEST, start, end, 1));

        assertThat(claims).filteredOn(QuotaClaim::granted).hasSize(10);
        assertThat(quotas.findUsageWindow("tenant-a", QuotaDimension.REQUEST, start))
                .get().extracting(QuotaUsageWindow::usedUnits).isEqualTo(10L);
        QuotaClaim denied = quotas.tryClaimUsage("tenant-a", QuotaDimension.REQUEST, start, end, 1);
        assertThat(denied.granted()).isFalse();
        assertThat(denied.usedUnits()).isEqualTo(10);
        assertThat(denied.remainingUnits()).isZero();
    }

    @Test
    void tryClaimUsage_DeniesMissingOrZeroQuotaWithoutCreatingUsage() {
        Instant start = Instant.parse("2026-09-16T01:00:00Z");
        Instant end = start.plus(1, ChronoUnit.HOURS);

        QuotaClaim claim = quotas.tryClaimUsage(
                "missing", QuotaDimension.KNOWLEDGE_IMPORT, start, end, 1);

        assertThat(claim.granted()).isFalse();
        assertThat(claim.limitUnits()).isZero();
        assertThat(quotas.findUsageWindow("missing", QuotaDimension.KNOWLEDGE_IMPORT, start)).isEmpty();
    }

    @Test
    void tryAcquireLease_UsesAtomicSlotsAndSupportsReleaseAndExpiry() throws Exception {
        quotas.saveLimits("tenant-a", new QuotaLimits(100, 100, 100, 3));
        JdbcTenantQuotaRepository secondInstance = new JdbcTenantQuotaRepository(jdbc);
        Instant now = Instant.parse("2026-09-16T02:00:00Z");

        List<Optional<ConcurrencyLease>> attempts = runConcurrently(16, index ->
                (index % 2 == 0 ? quotas : secondInstance).tryAcquireLease(
                        "tenant-a", "lease-" + index, "worker-" + index, now, now.plusSeconds(30)));

        List<ConcurrencyLease> acquired = attempts.stream().flatMap(Optional::stream).toList();
        assertThat(acquired).hasSize(3);
        assertThat(acquired).extracting(ConcurrencyLease::slot).doesNotHaveDuplicates();
        assertThat(quotas.countActiveLeases("tenant-a", now)).isEqualTo(3);

        ConcurrencyLease released = acquired.getFirst();
        assertThat(quotas.releaseLease("tenant-a", released.leaseId())).isTrue();
        assertThat(quotas.tryAcquireLease("tenant-a", "replacement", "worker-r", now, now.plusSeconds(20)))
                .isPresent();
        assertThat(quotas.deleteExpiredLeases(now.plusSeconds(30))).isEqualTo(3);
        assertThat(quotas.countActiveLeases("tenant-a", now.plusSeconds(30))).isZero();
    }

    @Test
    void tryAcquireLease_IsIdempotentForSameOwner() {
        quotas.saveLimits("tenant-a", new QuotaLimits(1, 1, 1, 1));
        Instant now = Instant.parse("2026-09-16T03:00:00Z");

        ConcurrencyLease first = quotas.tryAcquireLease(
                "tenant-a", "stable", "worker-a", now, now.plusSeconds(30)).orElseThrow();
        ConcurrencyLease second = quotas.tryAcquireLease(
                "tenant-a", "stable", "worker-a", now.plusSeconds(1), now.plusSeconds(40)).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(quotas.tryAcquireLease(
                "tenant-a", "stable", "worker-b", now.plusSeconds(1), now.plusSeconds(40))).isEmpty();
        assertThat(quotas.countActiveLeases("tenant-a", now)).isOne();
    }

    private static <T> List<T> runConcurrently(int count, IndexedOperation<T> operation) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int captured = index;
                tasks.add(() -> operation.execute(captured));
            }
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get());
            return results;
        }
    }

    private static JdbcDataSource initializedDataSource(String mode) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:xnlp-quota-" + mode + '-' + System.nanoTime()
                + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__identity-rbac.sql"),
                new ClassPathResource("db/migration/V10__tenant-quota.sql")).execute(dataSource);
        return dataSource;
    }

    @FunctionalInterface
    private interface IndexedOperation<T> {
        T execute(int index) throws Exception;
    }
}
