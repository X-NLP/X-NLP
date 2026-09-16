package com.xnlp.server.evaluation.recovery;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Evaluation recovery repository contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvaluationRecoveryRepositoryContractTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    Stream<Arguments> repositories() {
        return Stream.of(
                Arguments.of(Named.of("memory", (Supplier<EvaluationRecoveryRepository>)
                        InMemoryEvaluationRecoveryRepository::new)),
                Arguments.of(Named.of("jdbc", (Supplier<EvaluationRecoveryRepository>)
                        EvaluationRecoveryRepositoryContractTest::jdbcRepository)));
    }

    @ParameterizedTest(name = "{0} scopes runs, checkpoints, and samples by tenant")
    @MethodSource("repositories")
    void storage_IsTenantScoped(Supplier<EvaluationRecoveryRepository> factory) {
        EvaluationRecoveryRepository repository = factory.get();
        RecoveryRun tenantA = repository.createRun("tenant-a", "run-1", "dataset", 3, 2, "alice", NOW);
        RecoveryRun tenantB = repository.createRun("tenant-b", "run-1", "dataset", 7, 1, "bob", NOW);

        assertThat(repository.findRun("tenant-a", "run-1")).contains(tenantA);
        assertThat(repository.findRun("tenant-b", "run-1")).contains(tenantB);
        assertThat(repository.findRun("tenant-c", "run-1")).isEmpty();
        assertThat(repository.findCheckpoint("tenant-a", "run-1")).get().satisfies(checkpoint -> {
            assertThat(checkpoint.processedSamples()).isZero();
            assertThat(checkpoint.fencingToken()).isZero();
        });
        assertThat(repository.findSampleResults("tenant-b", "run-1")).isEmpty();
    }

    @ParameterizedTest(name = "{0} atomically claims, renews, releases, and fences leases")
    @MethodSource("repositories")
    void leaseLifecycle_FencesOldWorkers(Supplier<EvaluationRecoveryRepository> factory) {
        EvaluationRecoveryRepository repository = factory.get();
        repository.createRun("tenant-a", "run-1", "dataset", 0, 1, "alice", NOW);

        RecoveryLease first = repository.tryClaimLease(
                "tenant-a", "run-1", "worker-a", NOW, NOW.plusSeconds(30)).orElseThrow();
        assertThat(first.fencingToken()).isEqualTo(1);
        assertThat(repository.tryClaimLease(
                "tenant-a", "run-1", "worker-b", NOW.plusSeconds(1), NOW.plusSeconds(31))).isEmpty();
        assertThat(repository.tryClaimLease(
                "tenant-a", "run-1", "worker-a", NOW.plusSeconds(1), NOW.plusSeconds(31))).contains(first);

        RecoveryLease renewed = repository.renewLease(
                "tenant-a", "run-1", "worker-a", first.fencingToken(),
                NOW.plusSeconds(10), NOW.plusSeconds(60)).orElseThrow();
        assertThat(renewed.acquiredAt()).isEqualTo(NOW);
        assertThat(renewed.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(repository.releaseLease("tenant-a", "run-1", "worker-b", first.fencingToken())).isFalse();
        assertThat(repository.releaseLease("tenant-a", "run-1", "worker-a", first.fencingToken())).isTrue();

        RecoveryLease second = repository.tryClaimLease(
                "tenant-a", "run-1", "worker-b", NOW.plusSeconds(11), NOW.plusSeconds(41)).orElseThrow();
        assertThat(second.fencingToken()).isEqualTo(2);
        assertThat(repository.renewLease(
                "tenant-a", "run-1", "worker-a", first.fencingToken(),
                NOW.plusSeconds(12), NOW.plusSeconds(42))).isEmpty();
        assertThat(repository.tryClaimLease(
                "tenant-a", "run-1", "worker-c", NOW.plusSeconds(41), NOW.plusSeconds(71)))
                .get().extracting(RecoveryLease::fencingToken).isEqualTo(3L);
    }

    @ParameterizedTest(name = "{0} upserts immutable sample results idempotently")
    @MethodSource("repositories")
    void sampleResults_AreIdempotentAndLeaseFenced(Supplier<EvaluationRecoveryRepository> factory) {
        EvaluationRecoveryRepository repository = factory.get();
        repository.createRun("tenant-a", "run-1", "dataset", 4, 2, "alice", NOW);
        RecoveryLease lease = repository.tryClaimLease(
                "tenant-a", "run-1", "worker-a", NOW, NOW.plusSeconds(30)).orElseThrow();
        EvaluationSampleResult succeeded = success("run-1", "sample-1", 0, "key-1", NOW.plusSeconds(1));

        assertThat(repository.upsertSampleResult(
                succeeded, "worker-a", lease.fencingToken(), NOW.plusSeconds(1))).isEqualTo(succeeded);
        assertThat(repository.upsertSampleResult(
                succeeded, "worker-a", lease.fencingToken(), NOW.plusSeconds(2))).isEqualTo(succeeded);
        assertThat(repository.findSampleResults("tenant-a", "run-1")).containsExactly(succeeded);
        assertThat(repository.findSampleResults("tenant-b", "run-1")).isEmpty();

        EvaluationSampleResult changed = success("run-1", "sample-1", 0, "key-1", NOW.plusSeconds(2));
        assertThatThrownBy(() -> repository.upsertSampleResult(
                changed, "worker-a", lease.fencingToken(), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("idempotency conflict");
        assertThatThrownBy(() -> repository.upsertSampleResult(
                success("run-1", "sample-2", 1, "key-2", NOW.plusSeconds(2)),
                "worker-b", lease.fencingToken(), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
    }

    @ParameterizedTest(name = "{0} advances checkpoints monotonically under the current fencing token")
    @MethodSource("repositories")
    void checkpoint_IsMonotonicAndLeaseFenced(Supplier<EvaluationRecoveryRepository> factory) {
        EvaluationRecoveryRepository repository = factory.get();
        repository.createRun("tenant-a", "run-1", "dataset", 0, 3, "alice", NOW);
        RecoveryLease first = repository.tryClaimLease(
                "tenant-a", "run-1", "worker-a", NOW, NOW.plusSeconds(30)).orElseThrow();
        EvaluationCheckpoint checkpoint = new EvaluationCheckpoint(
                "tenant-a", "run-1", 1, 1, 1, 0, "sample-1", first.fencingToken(), NOW.plusSeconds(1));

        assertThat(repository.saveCheckpoint(checkpoint, "worker-a", NOW.plusSeconds(1))).isTrue();
        assertThat(repository.saveCheckpoint(new EvaluationCheckpoint(
                "tenant-a", "run-1", 0, 0, 0, 0, null, first.fencingToken(), NOW.plusSeconds(2)),
                "worker-a", NOW.plusSeconds(2))).isFalse();
        repository.releaseLease("tenant-a", "run-1", "worker-a", first.fencingToken());
        RecoveryLease second = repository.tryClaimLease(
                "tenant-a", "run-1", "worker-b", NOW.plusSeconds(3), NOW.plusSeconds(33)).orElseThrow();

        assertThat(repository.saveCheckpoint(new EvaluationCheckpoint(
                "tenant-a", "run-1", 2, 2, 1, 1, "sample-2", first.fencingToken(), NOW.plusSeconds(4)),
                "worker-a", NOW.plusSeconds(4))).isFalse();
        assertThat(repository.saveCheckpoint(new EvaluationCheckpoint(
                "tenant-a", "run-1", 2, 2, 1, 1, "sample-2", second.fencingToken(), NOW.plusSeconds(4)),
                "worker-b", NOW.plusSeconds(4))).isTrue();
        assertThat(repository.findCheckpoint("tenant-a", "run-1")).get()
                .extracting(EvaluationCheckpoint::processedSamples, EvaluationCheckpoint::fencingToken)
                .containsExactly(2, 2L);
    }

    @ParameterizedTest(name = "{0} applies one lease-fenced terminal CAS")
    @MethodSource("repositories")
    void terminalTransition_IsCasAndFenced(Supplier<EvaluationRecoveryRepository> factory) {
        EvaluationRecoveryRepository repository = factory.get();
        repository.createRun("tenant-a", "run-1", "dataset", 0, 1, "alice", NOW);
        RecoveryLease lease = repository.tryClaimLease(
                "tenant-a", "run-1", "worker-a", NOW, NOW.plusSeconds(30)).orElseThrow();

        assertThat(repository.markRunning(
                "tenant-a", "run-1", "worker-a", lease.fencingToken(), NOW.plusSeconds(1))).isTrue();
        assertThat(repository.transitionToTerminal(
                "tenant-a", "run-1", RecoveryRunStatus.QUEUED, RecoveryRunStatus.COMPLETED,
                "worker-a", lease.fencingToken(), null, NOW.plusSeconds(2))).isFalse();
        assertThat(repository.transitionToTerminal(
                "tenant-a", "run-1", RecoveryRunStatus.RUNNING, RecoveryRunStatus.COMPLETED,
                "worker-b", lease.fencingToken(), null, NOW.plusSeconds(2))).isFalse();
        assertThat(repository.transitionToTerminal(
                "tenant-a", "run-1", RecoveryRunStatus.RUNNING, RecoveryRunStatus.COMPLETED,
                "worker-a", lease.fencingToken(), null, NOW.plusSeconds(2))).isTrue();
        assertThat(repository.transitionToTerminal(
                "tenant-a", "run-1", RecoveryRunStatus.RUNNING, RecoveryRunStatus.FAILED,
                "worker-a", lease.fencingToken(), "late", NOW.plusSeconds(3))).isFalse();
        assertThat(repository.findRun("tenant-a", "run-1")).get().satisfies(run -> {
            assertThat(run.status()).isEqualTo(RecoveryRunStatus.COMPLETED);
            assertThat(run.completedAt()).isEqualTo(NOW.plusSeconds(2));
        });
        assertThat(repository.tryClaimLease(
                "tenant-a", "run-1", "worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60))).isEmpty();
    }

    @ParameterizedTest(name = "{0} persists retry lineage and selects failed samples")
    @MethodSource("repositories")
    void retryLineage_PreservesDatasetVersionAndFailedSelection(Supplier<EvaluationRecoveryRepository> factory) {
        EvaluationRecoveryRepository repository = factory.get();
        repository.createRun("tenant-a", "run-1", "dataset", 9, 2, "alice", NOW);
        RecoveryLease lease = repository.tryClaimLease(
                "tenant-a", "run-1", "worker-a", NOW, NOW.plusSeconds(30)).orElseThrow();
        repository.markRunning("tenant-a", "run-1", "worker-a", lease.fencingToken(), NOW.plusSeconds(1));
        repository.upsertSampleResult(success("run-1", "sample-1", 0, "key-1", NOW.plusSeconds(2)),
                "worker-a", lease.fencingToken(), NOW.plusSeconds(2));
        EvaluationSampleResult failed = new EvaluationSampleResult(
                "tenant-a", "run-1", "sample-2", 1, SampleResultStatus.FAILED,
                "expected", null, null, "provider_timeout", "timed out", "key-2", NOW.plusSeconds(3));
        repository.upsertSampleResult(failed, "worker-a", lease.fencingToken(), NOW.plusSeconds(3));
        repository.transitionToTerminal("tenant-a", "run-1", RecoveryRunStatus.RUNNING,
                RecoveryRunStatus.FAILED, "worker-a", lease.fencingToken(), "one sample failed", NOW.plusSeconds(4));

        RecoveryRun retry = repository.createRetry(
                "tenant-a", "run-2", "run-1", true, "bob", NOW.plusSeconds(5));

        assertThat(retry).satisfies(run -> {
            assertThat(run.rootRunId()).isEqualTo("run-1");
            assertThat(run.parentRunId()).isEqualTo("run-1");
            assertThat(run.datasetVersion()).isEqualTo(9);
            assertThat(run.attempt()).isOne();
            assertThat(run.retryFailedOnly()).isTrue();
            assertThat(run.totalSamples()).isOne();
        });
        assertThat(repository.findFailedSampleIds("tenant-a", "run-1")).containsExactly("sample-2");
        assertThat(repository.findRetryLineage("tenant-a", "run-1"))
                .extracting(RecoveryRun::runId).containsExactly("run-1", "run-2");
        assertThat(repository.findRetryLineage("tenant-b", "run-1")).isEmpty();
    }

    private static EvaluationSampleResult success(
            String runId, String sampleId, int sequence, String key, Instant completedAt) {
        return new EvaluationSampleResult("tenant-a", runId, sampleId, sequence,
                SampleResultStatus.SUCCEEDED, "expected", "actual", "{\"score\":1}",
                null, null, key, completedAt);
    }

    private static EvaluationRecoveryRepository jdbcRepository() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:contract-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__identity-rbac.sql"),
                new org.springframework.core.io.ByteArrayResource(v12Sql())).execute(dataSource);
        return new JdbcEvaluationRecoveryRepository(new JdbcTemplate(dataSource));
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

}
