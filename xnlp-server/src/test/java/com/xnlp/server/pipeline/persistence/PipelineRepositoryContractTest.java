package com.xnlp.server.pipeline.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Pipeline repository contract")
class PipelineRepositoryContractTest {

    private static final Instant NOW = Instant.parse("2026-09-16T13:00:00Z");

    @Nested
    @DisplayName("memory")
    class MemoryContract extends Contract {
        @Override
        protected PipelineRepository repository() {
            return new InMemoryPipelineRepository();
        }
    }

    @Nested
    @DisplayName("jdbc")
    class JdbcContract extends Contract {
        @Override
        protected PipelineRepository repository() {
            return new JdbcPipelineRepository(new JdbcTemplate(initializedDataSource("MySQL")));
        }
    }

    abstract static class Contract {

        protected abstract PipelineRepository repository();

        @Test
        void definitions_AreVersionedTenantScopedAndImmutable() {
            PipelineRepository repository = repository();
            PipelineDefinition original = definition("tenant-a", "pipeline", "Original", NOW);
            repository.createDefinition(original);
            repository.createDefinition(definition("tenant-b", "pipeline", "Other", NOW));

            PipelineDefinition requested = definition("tenant-a", "pipeline", "Updated", NOW.plusSeconds(1));
            PipelineWriteResult applied = repository.updateDefinition(requested, 0);
            PipelineWriteResult stale = repository.updateDefinition(
                    definition("tenant-a", "pipeline", "Stale", NOW.plusSeconds(2)), 0);

            assertThat(applied.status()).isEqualTo(PipelineWriteStatus.APPLIED);
            assertThat(applied.definition().version()).isEqualTo(1);
            assertThat(stale.status()).isEqualTo(PipelineWriteStatus.CONFLICT);
            assertThat(stale.definition().name()).isEqualTo("Updated");
            assertThat(repository.findDefinitionVersion("tenant-a", "pipeline", 0)).get()
                    .extracting(PipelineDefinition::name).isEqualTo("Original");
            assertThat(repository.findDefinition("tenant-a", "pipeline")).get()
                    .extracting(PipelineDefinition::name).isEqualTo("Updated");
            assertThat(repository.findDefinition("tenant-b", "pipeline")).get()
                    .extracting(PipelineDefinition::name).isEqualTo("Other");
            assertThat(repository.findDefinition("tenant-c", "pipeline")).isEmpty();
        }

        @Test
        void runCasAndCancellation_ProtectTerminalStateAndLateNodeWrites() {
            PipelineRepository repository = repository();
            repository.createDefinition(definition("tenant-a", "pipeline", "Pipeline", NOW));
            repository.createRun(run("tenant-a", "run", NOW));

            assertThat(repository.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.QUEUED,
                    PipelineRunStatus.RUNNING, 0, NOW.plusSeconds(1), null, null, null)).isTrue();
            assertThat(repository.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.QUEUED,
                    PipelineRunStatus.RUNNING, 0, NOW.plusSeconds(1), null, null, null)).isFalse();
            repository.createNodeAttempt(attempt("tenant-a", "run", 1, NOW.plusSeconds(2)));

            assertThat(repository.requestCancellation("tenant-a", "run", 1, NOW.plusSeconds(3))).isTrue();
            assertThat(repository.completeNodeAttempt("tenant-a", "run", "normalize", 1,
                    NodeAttemptStatus.RUNNING, NodeAttemptStatus.SUCCEEDED, NOW.plusSeconds(4),
                    "{\"text\":\"late\"}", null, null)).isFalse();
            assertThatThrownBy(() -> repository.createNodeAttempt(
                    attempt("tenant-a", "run", 2, NOW.plusSeconds(4))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(repository.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.CANCELLING,
                    PipelineRunStatus.CANCELLED, 2, NOW.plusSeconds(5), null, null, null)).isTrue();
            assertThat(repository.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.CANCELLED,
                    PipelineRunStatus.COMPLETED, 3, NOW.plusSeconds(6), "{}", null, null)).isFalse();
            assertThat(repository.requestCancellation("tenant-a", "run", 3, NOW.plusSeconds(6))).isFalse();

            assertThat(repository.findRun("tenant-a", "run")).get().satisfies(found -> {
                assertThat(found.status()).isEqualTo(PipelineRunStatus.CANCELLED);
                assertThat(found.cancelRequested()).isTrue();
                assertThat(found.revision()).isEqualTo(3);
            });
            assertThat(repository.findRun("tenant-b", "run")).isEmpty();
        }

        @Test
        void nodeAttemptsAndEvents_AreTenantScopedAndOrdered() {
            PipelineRepository repository = repository();
            repository.createDefinition(definition("tenant-a", "pipeline", "Pipeline", NOW));
            repository.createRun(run("tenant-a", "run", NOW));
            repository.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.QUEUED,
                    PipelineRunStatus.RUNNING, 0, NOW.plusSeconds(1), null, null, null);
            repository.createNodeAttempt(attempt("tenant-a", "run", 1, NOW.plusSeconds(2)));
            assertThat(repository.completeNodeAttempt("tenant-a", "run", "normalize", 1,
                    NodeAttemptStatus.RUNNING, NodeAttemptStatus.FAILED, NOW.plusSeconds(3),
                    null, "provider_failure", "failed")).isTrue();
            repository.createNodeAttempt(attempt("tenant-a", "run", 2, NOW.plusSeconds(4)));
            repository.completeNodeAttempt("tenant-a", "run", "normalize", 2,
                    NodeAttemptStatus.RUNNING, NodeAttemptStatus.SUCCEEDED, NOW.plusSeconds(5),
                    "{\"text\":\"ok\"}", null, null);

            PipelineRunEvent first = repository.appendEvent(event("tenant-a", "run", "run.started", NOW));
            PipelineRunEvent second = repository.appendEvent(
                    event("tenant-a", "run", "node.failed", NOW.plusSeconds(1)));
            PipelineRunEvent third = repository.appendEvent(
                    event("tenant-a", "run", "node.succeeded", NOW.plusSeconds(2)));

            assertThat(List.of(first.sequence(), second.sequence(), third.sequence()))
                    .containsExactly(1L, 2L, 3L);
            assertThat(repository.findEvents("tenant-a", "run", 1, 1))
                    .extracting(PipelineRunEvent::eventType).containsExactly("node.failed");
            assertThat(repository.findEvents("tenant-b", "run", 0, 10)).isEmpty();
            assertThat(repository.findNodeAttempts("tenant-a", "run"))
                    .extracting(PipelineNodeAttempt::attempt).containsExactly(1, 2);
            assertThat(repository.findNodeAttempts("tenant-b", "run")).isEmpty();
        }
    }

    @Test
    void jdbcDefinitionCas_AllowsOneConcurrentWinner() throws Exception {
        JdbcDataSource dataSource = initializedDataSource("PostgreSQL");
        PipelineRepository first = new JdbcPipelineRepository(new JdbcTemplate(dataSource));
        PipelineRepository second = new JdbcPipelineRepository(new JdbcTemplate(dataSource));
        first.createDefinition(definition("tenant-a", "pipeline", "Initial", NOW));

        List<PipelineWriteResult> results = concurrently(
                () -> first.updateDefinition(definition("tenant-a", "pipeline", "First", NOW.plusSeconds(1)), 0),
                () -> second.updateDefinition(definition("tenant-a", "pipeline", "Second", NOW.plusSeconds(1)), 0));

        assertThat(results).filteredOn(result -> result.status() == PipelineWriteStatus.APPLIED).hasSize(1);
        assertThat(results).filteredOn(result -> result.status() == PipelineWriteStatus.CONFLICT).hasSize(1);
        assertThat(first.findDefinition("tenant-a", "pipeline")).get()
                .extracting(PipelineDefinition::version).isEqualTo(1L);
    }

    @Test
    void jdbcRunCas_AllowsOneConcurrentTerminalTransition() throws Exception {
        JdbcDataSource dataSource = initializedDataSource("PostgreSQL");
        PipelineRepository first = new JdbcPipelineRepository(new JdbcTemplate(dataSource));
        PipelineRepository second = new JdbcPipelineRepository(new JdbcTemplate(dataSource));
        first.createDefinition(definition("tenant-a", "pipeline", "Pipeline", NOW));
        first.createRun(run("tenant-a", "run", NOW));
        first.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.QUEUED,
                PipelineRunStatus.RUNNING, 0, NOW.plusSeconds(1), null, null, null);

        List<Boolean> results = concurrently(
                () -> first.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.RUNNING,
                        PipelineRunStatus.COMPLETED, 1, NOW.plusSeconds(2), "{}", null, null),
                () -> second.compareAndSetRunStatus("tenant-a", "run", PipelineRunStatus.RUNNING,
                        PipelineRunStatus.FAILED, 1, NOW.plusSeconds(2), null, "failure", "failed"));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(first.findRun("tenant-a", "run")).get()
                .extracting(PipelineRun::revision).isEqualTo(2L);
    }

    @Test
    void jdbcEvents_AllocateUniqueOrderedSequencesConcurrently() throws Exception {
        JdbcDataSource dataSource = initializedDataSource("PostgreSQL");
        PipelineRepository repository = new JdbcPipelineRepository(new JdbcTemplate(dataSource));
        PipelineRepository second = new JdbcPipelineRepository(new JdbcTemplate(dataSource));
        repository.createDefinition(definition("tenant-a", "pipeline", "Pipeline", NOW));
        repository.createRun(run("tenant-a", "run", NOW));

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<Callable<PipelineRunEvent>> tasks = java.util.stream.IntStream.range(0, 30)
                    .mapToObj(index -> (Callable<PipelineRunEvent>) () ->
                            (index % 2 == 0 ? repository : second).appendEvent(
                                    event("tenant-a", "run", "event-" + index, NOW.plusMillis(index))))
                    .toList();
            for (Future<PipelineRunEvent> future : executor.invokeAll(tasks)) future.get();
        }

        assertThat(repository.findEvents("tenant-a", "run", 0, 100))
                .extracting(PipelineRunEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 30).boxed().toList());
    }

    @Test
    void migration_IsPortableAcrossSupportedH2CompatibilityModes() {
        for (String mode : List.of("MySQL", "PostgreSQL")) {
            JdbcTemplate jdbc = new JdbcTemplate(initializedDataSource(mode));
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME IN ('PIPELINE_DEFINITIONS', 'PIPELINE_DEFINITION_VERSIONS',
                      'PIPELINE_NODES', 'PIPELINE_EDGES', 'PIPELINE_RUNS', 'PIPELINE_NODE_ATTEMPTS',
                      'PIPELINE_RUN_EVENT_SEQUENCES', 'PIPELINE_RUN_EVENTS')
                    """, Integer.class)).isEqualTo(8);
        }
    }

    private static PipelineDefinition definition(String tenant, String id, String name, Instant now) {
        return new PipelineDefinition(tenant, id, name, "description", 0,
                List.of(
                        new PipelineNodeDefinition("normalize", "text-normalization", "{}", 1_000, 2),
                        new PipelineNodeDefinition("summarize", "summarization", "{}", 2_000, 1)),
                List.of(new PipelineEdgeDefinition("normalize", "summarize", "text", "input")),
                "alice", now, now);
    }

    private static PipelineRun run(String tenant, String runId, Instant now) {
        return new PipelineRun(tenant, runId, "pipeline", 0, PipelineRunStatus.QUEUED,
                "{\"text\":\"input\"}", null, null, null, false, 0,
                "alice", now, null, now, null);
    }

    private static PipelineNodeAttempt attempt(String tenant, String runId, int number, Instant now) {
        return new PipelineNodeAttempt(tenant, runId, "normalize", number, NodeAttemptStatus.RUNNING,
                "{\"text\":\"input\"}", null, null, null, now, null);
    }

    private static PipelineRunEvent event(String tenant, String runId, String type, Instant now) {
        return new PipelineRunEvent(tenant, runId, 0, type, null, null, "{}", now);
    }

    @SafeVarargs
    private static <T> List<T> concurrently(Callable<T>... operations) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(operations.length)) {
            List<Future<T>> futures = executor.invokeAll(List.of(operations));
            return futures.stream().map(PipelineRepositoryContractTest::get).toList();
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JdbcDataSource initializedDataSource(String mode) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pipeline-" + mode + '-' + System.nanoTime()
                + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__identity-rbac.sql"),
                new ByteArrayResource(v13Sql())).execute(dataSource);
        return dataSource;
    }

    private static byte[] v13Sql() {
        try {
            return new ClassPathResource("db/migration/V13__pipeline-dag.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("__LARGE_TEXT__", "TEXT")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
