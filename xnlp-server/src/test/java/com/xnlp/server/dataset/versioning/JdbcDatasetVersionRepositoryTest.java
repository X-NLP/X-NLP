package com.xnlp.server.dataset.versioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.eval.NLPTaskType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Portable JDBC dataset version repository")
class JdbcDatasetVersionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-16T08:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcDatasetVersionRepository datasets;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = initializedDataSource("MySQL");
        jdbc = new JdbcTemplate(dataSource);
        datasets = new JdbcDatasetVersionRepository(jdbc, new ObjectMapper());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void migrationSql_LoadsInPostgreSqlCompatibilityMode() {
        JdbcTemplate postgresql = new JdbcTemplate(initializedDataSource("PostgreSQL"));

        assertThat(postgresql.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN "
                        + "('VERSIONED_DATASETS', 'VERSIONED_DATASET_ENTRIES', 'DATASET_VERSION_SNAPSHOTS', "
                        + "'DATASET_IMPORT_JOBS', 'DATASET_IMPORT_ERRORS')",
                Integer.class)).isEqualTo(5);
    }

    @Test
    void bootstrap_ImportsExistingDatasetAtomicallyAtVersionZeroAndIsTenantScoped() {
        DatasetVersionBootstrap bootstrap = new DatasetVersionBootstrap(datasets);
        EvaluationDataset source = evaluationDataset("quality", "Quality", List.of(
                evaluationEntry("row-1", "alpha", "one"),
                evaluationEntry("row-2", "bravo", "two")));

        VersionedDataset first = transaction.execute(status ->
                bootstrap.bootstrap("tenant-a", source, "migration", NOW));
        source.setName("Changed source");
        source.setEntries(List.of(evaluationEntry("row-3", "charlie", "three")));
        VersionedDataset repeated = transaction.execute(status ->
                bootstrap.bootstrap("tenant-a", source, "migration", NOW.plusSeconds(1)));
        VersionedDataset otherTenant = transaction.execute(status ->
                bootstrap.bootstrap("tenant-b", source, "migration", NOW.plusSeconds(2)));

        assertThat(first.version()).isZero();
        assertThat(first.entryCount()).isEqualTo(2);
        assertThat(repeated).isEqualTo(first);
        assertThat(datasets.findEntries("tenant-a", "quality"))
                .extracting(value -> value.data().id()).containsExactly("row-1", "row-2");
        assertThat(datasets.findSnapshot("tenant-a", "quality", 0)).get().satisfies(snapshot -> {
            assertThat(snapshot.entries()).hasSize(2);
            assertThat(snapshot.metadata().name()).isEqualTo("Quality");
        });
        assertThat(otherTenant.version()).isZero();
        assertThat(datasets.findEntries("tenant-b", "quality"))
                .extracting(value -> value.data().id()).containsExactly("row-3");
    }

    @Test
    void bootstrap_RollsBackDatasetAndEntriesWhenAnyEntryCannotBePersisted() {
        DatasetEntryData valid = entry("row-1", 0, "alpha");
        DatasetEntryData invalid = new DatasetEntryData("row-2", 1, "bravo", "expected",
                Map.of("invalid", new Object() { public Object self() { return this; } }), Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.execute(status ->
                datasets.bootstrapDataset("tenant-a", "quality", metadata("Quality"), List.of(valid, invalid),
                        "migration", NOW, NOW.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(datasets.findDataset("tenant-a", "quality")).isEmpty();
        assertThat(datasets.findEntries("tenant-a", "quality")).isEmpty();
        assertThat(datasets.findSnapshot("tenant-a", "quality", 0)).isEmpty();
    }

    @Test
    void putEntry_RollsBackVersionClaimWhenEntryWriteFails() {
        datasets.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        DatasetEntryData unserializable = new DatasetEntryData("row-1", 0, "alpha", "expected",
                Map.of("invalid", new Object() { public Object self() { return this; } }), Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.execute(status ->
                datasets.putEntry("tenant-a", "quality", 0, unserializable, NOW.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(datasets.findDataset("tenant-a", "quality")).get().satisfies(dataset -> {
            assertThat(dataset.version()).isZero();
            assertThat(dataset.entryCount()).isZero();
        });
        assertThat(datasets.findEntries("tenant-a", "quality")).isEmpty();
    }

    @Test
    void entryCrud_UsesExpectedVersionCasAndTenantIsolation() throws Exception {
        datasets.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        datasets.createDataset("tenant-b", "quality", metadata("Other"), NOW);

        DatasetWriteResult<VersionedDatasetEntry> first = datasets.putEntry(
                "tenant-a", "quality", 0, entry("row-1", 0, "alpha"), NOW.plusSeconds(1));
        assertThat(first.status()).isEqualTo(DatasetWriteStatus.APPLIED);
        assertThat(first.currentVersion()).isEqualTo(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<DatasetWriteResult<VersionedDatasetEntry>>> writes = List.of(
                    () -> transaction.execute(status -> datasets.putEntry("tenant-a", "quality", 1,
                            entry("row-2", 1, "bravo"), NOW.plusSeconds(2))),
                    () -> transaction.execute(status -> new JdbcDatasetVersionRepository(jdbc, new ObjectMapper())
                            .putEntry("tenant-a", "quality", 1,
                                    entry("row-3", 2, "charlie"), NOW.plusSeconds(2))));
            List<Future<DatasetWriteResult<VersionedDatasetEntry>>> futures = executor.invokeAll(writes);
            assertThat(futures.stream().map(this::get).map(DatasetWriteResult::status).toList())
                    .containsExactlyInAnyOrder(DatasetWriteStatus.APPLIED, DatasetWriteStatus.VERSION_CONFLICT);
        }

        VersionedDataset tenantA = datasets.findDataset("tenant-a", "quality").orElseThrow();
        assertThat(tenantA.version()).isEqualTo(2);
        assertThat(tenantA.entryCount()).isEqualTo(2);
        assertThat(datasets.findEntries("tenant-a", "quality")).hasSize(2);
        assertThat(datasets.findEntries("tenant-b", "quality")).isEmpty();
        assertThat(datasets.putEntry("tenant-b", "quality", 0,
                entry("row-b", 0, "private"), NOW.plusSeconds(3)).applied()).isTrue();
        assertThat(datasets.findEntries("tenant-a", "quality"))
                .extracting(value -> value.data().input()).doesNotContain("private");

        String removedId = datasets.findEntries("tenant-a", "quality").getFirst().data().id();
        DatasetWriteResult<VersionedDataset> deleted = datasets.deleteEntry(
                "tenant-a", "quality", removedId, 2, NOW.plusSeconds(4));
        assertThat(deleted.applied()).isTrue();
        assertThat(deleted.value().version()).isEqualTo(3);
        assertThat(deleted.value().entryCount()).isEqualTo(1);
    }

    @Test
    void snapshot_IsImmutableTenantScopedAndIdempotent() {
        datasets.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        datasets.putEntry("tenant-a", "quality", 0, entry("row-1", 0, "before"), NOW.plusSeconds(1));

        DatasetSnapshot first = datasets.createSnapshot(
                "tenant-a", "quality", 1, "alice", NOW.plusSeconds(2)).value();
        DatasetSnapshot repeated = datasets.createSnapshot(
                "tenant-a", "quality", 1, "bob", NOW.plusSeconds(3)).value();
        datasets.putEntry("tenant-a", "quality", 1, entry("row-1", 0, "after"), NOW.plusSeconds(4));

        assertThat(repeated).isEqualTo(first);
        assertThat(datasets.findSnapshot("tenant-a", "quality", 1)).get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.createdBy()).isEqualTo("alice");
                    assertThat(snapshot.entries()).singleElement()
                            .extracting(value -> value.data().input()).isEqualTo("before");
                });
        assertThat(datasets.findSnapshot("tenant-b", "quality", 1)).isEmpty();
        assertThat(datasets.createSnapshot("tenant-a", "quality", 1, "alice", NOW.plusSeconds(5)).status())
                .isEqualTo(DatasetWriteStatus.VERSION_CONFLICT);
    }

    @Test
    void importJobAndErrors_PersistReportAndLocateInvalidRows() {
        datasets.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        datasets.createDataset("tenant-b", "quality", metadata("Other"), NOW);
        DatasetImportJob job = datasets.createImportJob(
                "tenant-a", "import-1", "quality", "dataset.csv", 3, 0, "alice", NOW);
        datasets.appendImportError("tenant-a", "error-2", job.id(), 2,
                "missing_expected_output", "Expected output is required", "two,", NOW.plusSeconds(1));
        datasets.appendImportError("tenant-a", "error-3", job.id(), 3,
                "invalid_json", "Labels are invalid JSON", "three,{", NOW.plusSeconds(2));
        assertThat(datasets.updateImportJob("tenant-a", job.id(), DatasetImportStatus.COMPLETED_WITH_ERRORS,
                1, 2, 1L, "2 rows failed", NOW.plusSeconds(3), NOW.plusSeconds(3))).isTrue();

        assertThat(datasets.findImportJob("tenant-a", job.id())).get().satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(DatasetImportStatus.COMPLETED_WITH_ERRORS);
            assertThat(saved.importedRows()).isOne();
            assertThat(saved.failedRows()).isEqualTo(2);
            assertThat(saved.resultingVersion()).isEqualTo(1L);
        });
        assertThat(datasets.findImportErrors("tenant-a", job.id(), 10, 0))
                .extracting(DatasetImportError::rowNumber).containsExactly(2, 3);
        assertThat(datasets.findImportErrors("tenant-b", job.id(), 10, 0)).isEmpty();
        assertThat(datasets.findImportJob("tenant-b", job.id())).isEmpty();
    }

    @Test
    void metadataUpdate_ReportsConflictAndMissingDatasetWithoutCrossTenantLeak() {
        datasets.createDataset("tenant-a", "quality", metadata("Quality"), NOW);

        assertThat(datasets.updateDataset("tenant-a", "quality", 0,
                metadata("Updated"), NOW.plusSeconds(1)).value().metadata().name()).isEqualTo("Updated");
        assertThat(datasets.updateDataset("tenant-a", "quality", 0,
                metadata("Stale"), NOW.plusSeconds(2))).satisfies(result -> {
                    assertThat(result.status()).isEqualTo(DatasetWriteStatus.VERSION_CONFLICT);
                    assertThat(result.currentVersion()).isEqualTo(1);
                });
        assertThat(datasets.updateDataset("tenant-b", "quality", 0,
                metadata("Hidden"), NOW.plusSeconds(2)).status()).isEqualTo(DatasetWriteStatus.NOT_FOUND);
    }

    private static EvaluationDataset evaluationDataset(
            String id, String name, List<EvaluationEntry> entries) {
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setId(id);
        dataset.setName(name);
        dataset.setDescription("Regression data");
        dataset.setTaskType(NLPTaskType.TEXT_CLASSIFICATION);
        dataset.setEntries(entries);
        dataset.setCreatedAt(NOW.minusSeconds(10));
        dataset.setUpdatedAt(NOW.minusSeconds(5));
        return dataset;
    }

    private static EvaluationEntry evaluationEntry(String id, String input, String expected) {
        EvaluationEntry entry = new EvaluationEntry(id, input, expected);
        entry.setLabels(Map.of("label", "ok"));
        entry.setMetadata(Map.of("source", "legacy-api"));
        return entry;
    }

    private static DatasetMetadata metadata(String name) {
        return new DatasetMetadata(name, "Regression data", "TEXT_CLASSIFICATION");
    }

    private static DatasetEntryData entry(String id, int sequence, String input) {
        return new DatasetEntryData(id, sequence, input, "expected", Map.of("label", "ok"), Map.of("source", "test"));
    }

    private <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static JdbcDataSource initializedDataSource(String mode) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:xnlp-dataset-version-" + mode + '-' + System.nanoTime()
                + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__identity-rbac.sql"),
                portableResource("db/migration/V11__dataset-versioning.sql", mode)).execute(dataSource);
        return dataSource;
    }

    private static Resource portableResource(String location, String mode) {
        try {
            String sql = StreamUtils.copyToString(new ClassPathResource(location).getInputStream(),
                    StandardCharsets.UTF_8);
            String largeText = "PostgreSQL".equals(mode) ? "TEXT" : "LONGTEXT";
            return new org.springframework.core.io.ByteArrayResource(
                    sql.replace("__LARGE_TEXT__", largeText).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
