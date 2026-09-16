package com.xnlp.server.evaluation.recovery;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JDBC evaluation recovery repository")
class JdbcEvaluationRecoveryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"MySQL", "PostgreSQL"})
    void migration_IsPortableAcrossSupportedH2CompatibilityModes(String mode) {
        JdbcDataSource dataSource = initializedDataSource(mode);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcEvaluationRecoveryRepository repository = new JdbcEvaluationRecoveryRepository(jdbc);

        repository.createRun("tenant-a", "run-1", "dataset", 0, 1, "alice", NOW);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_recovery_runs", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_checkpoints", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_recovery_leases", Integer.class)).isOne();
    }

    @Test
    void tryClaimLease_AllowsOnlyOneConcurrentOwnerAcrossRepositoryInstances() throws Exception {
        JdbcDataSource dataSource = initializedDataSource("PostgreSQL");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcEvaluationRecoveryRepository repository = new JdbcEvaluationRecoveryRepository(jdbc);
        JdbcEvaluationRecoveryRepository second = new JdbcEvaluationRecoveryRepository(jdbc);
        repository.createRun("tenant-a", "run-1", "dataset", 0, 1, "alice", NOW);

        List<Optional<RecoveryLease>> claims = runConcurrently(20, index ->
                (index % 2 == 0 ? repository : second).tryClaimLease(
                        "tenant-a", "run-1", "worker-" + index, NOW, NOW.plusSeconds(30)));

        assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(repository.findLease("tenant-a", "run-1")).isPresent();
        assertThat(repository.findLease("tenant-a", "run-1").orElseThrow().fencingToken()).isEqualTo(1);
    }

    @Test
    void createRun_RollsBackRunWhenControlRowCreationFails() {
        JdbcDataSource dataSource = initializedDataSource("PostgreSQL");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcEvaluationRecoveryRepository repository = new JdbcEvaluationRecoveryRepository(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("""
                CREATE TRIGGER reject_checkpoint BEFORE INSERT ON evaluation_checkpoints
                FOR EACH ROW CALL 'com.xnlp.server.evaluation.recovery.RejectCheckpointTrigger'
                """);

        assertThatThrownBy(() -> transaction.execute(status -> repository.createRun(
                "tenant-a", "run-1", "dataset", 0, 1, "alice", NOW)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_recovery_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_checkpoints", Integer.class)).isZero();
    }

    @Test
    void staleLeaseCannotWriteAfterConcurrentTakeover() {
        JdbcDataSource dataSource = initializedDataSource("PostgreSQL");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcEvaluationRecoveryRepository first = new JdbcEvaluationRecoveryRepository(jdbc);
        JdbcEvaluationRecoveryRepository second = new JdbcEvaluationRecoveryRepository(jdbc);
        first.createRun("tenant-a", "run-1", "dataset", 0, 1, "alice", NOW);
        RecoveryLease stale = first.tryClaimLease(
                "tenant-a", "run-1", "worker-a", NOW, NOW.plusSeconds(5)).orElseThrow();
        RecoveryLease active = second.tryClaimLease(
                "tenant-a", "run-1", "worker-b", NOW.plusSeconds(5), NOW.plusSeconds(35)).orElseThrow();

        assertThat(active.fencingToken()).isEqualTo(stale.fencingToken() + 1);
        assertThat(first.markRunning(
                "tenant-a", "run-1", "worker-a", stale.fencingToken(), NOW.plusSeconds(6))).isFalse();
        assertThatThrownBy(() -> first.upsertSampleResult(new EvaluationSampleResult(
                "tenant-a", "run-1", "sample-1", 0, SampleResultStatus.SUCCEEDED,
                null, "late", "{}", null, null, "key-late", NOW.plusSeconds(6)),
                "worker-a", stale.fencingToken(), NOW.plusSeconds(6)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
        assertThat(second.markRunning(
                "tenant-a", "run-1", "worker-b", active.fencingToken(), NOW.plusSeconds(6))).isTrue();
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
        dataSource.setURL("jdbc:h2:mem:jdbc-" + mode + '-' + System.nanoTime()
                + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__identity-rbac.sql"),
                new org.springframework.core.io.ByteArrayResource(v12Sql())).execute(dataSource);
        return dataSource;
    }

    private static byte[] v12Sql() {
        try {
            return new ClassPathResource("db/migration/V12__resumable-evaluation.sql")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                    .replace("__LARGE_TEXT__", "TEXT").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    @FunctionalInterface
    private interface IndexedOperation<T> {
        T execute(int index) throws Exception;
    }
}
