package com.xnlp.server.dataset.versioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Dataset version repository contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetVersioningContractTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    Stream<Arguments> repositories() {
        return Stream.of(
                Arguments.of(Named.of("memory", (Supplier<DatasetVersionRepository>)
                        InMemoryDatasetVersionRepository::new)),
                Arguments.of(Named.of("jdbc", (Supplier<DatasetVersionRepository>)
                        DatasetVersioningContractTest::jdbcRepository)));
    }

    @ParameterizedTest(name = "{0} bootstraps version zero atomically and idempotently")
    @MethodSource("repositories")
    void bootstrap_CreatesCompleteVersionZeroAndPreservesExistingState(
            Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        List<DatasetEntryData> initial = List.of(
                entry("row-1", 0, "alpha"),
                entry("row-2", 1, "bravo"));

        VersionedDataset first = repository.bootstrapDataset("tenant-a", "quality", metadata("Quality"),
                initial, "migration", NOW, NOW.plusSeconds(1));
        VersionedDataset repeated = repository.bootstrapDataset("tenant-a", "quality", metadata("Changed"),
                List.of(entry("row-3", 0, "charlie")), "migration", NOW, NOW.plusSeconds(2));
        VersionedDataset otherTenant = repository.bootstrapDataset("tenant-b", "quality", metadata("Other"),
                List.of(entry("row-b", 0, "private")), "migration", NOW, NOW.plusSeconds(3));

        assertThat(first).isEqualTo(repeated);
        assertThat(first.version()).isZero();
        assertThat(first.entryCount()).isEqualTo(2);
        assertThat(repository.findEntries("tenant-a", "quality"))
                .extracting(value -> value.data().id()).containsExactly("row-1", "row-2");
        assertThat(repository.findSnapshot("tenant-a", "quality", 0)).get().satisfies(snapshot -> {
            assertThat(snapshot.entries()).hasSize(2);
            assertThat(snapshot.metadata().name()).isEqualTo("Quality");
        });
        assertThat(otherTenant.entryCount()).isOne();
        assertThat(repository.findEntries("tenant-b", "quality"))
                .extracting(value -> value.data().id()).containsExactly("row-b");
    }

    @ParameterizedTest(name = "{0} enforces expectedVersion CAS")
    @MethodSource("repositories")
    void writes_EnforceExpectedVersionCas(Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "quality", metadata("Quality"), NOW);

        DatasetWriteResult<VersionedDataset> metadataWrite = repository.updateDataset(
                "tenant-a", "quality", 0, metadata("Updated"), NOW.plusSeconds(1));
        DatasetWriteResult<VersionedDataset> staleMetadataWrite = repository.updateDataset(
                "tenant-a", "quality", 0, metadata("Stale"), NOW.plusSeconds(2));
        DatasetWriteResult<VersionedDatasetEntry> entryWrite = repository.putEntry(
                "tenant-a", "quality", 1, entry("row-1", 0, "alpha"), NOW.plusSeconds(3));
        DatasetWriteResult<VersionedDatasetEntry> staleEntryWrite = repository.putEntry(
                "tenant-a", "quality", 1, entry("row-2", 1, "bravo"), NOW.plusSeconds(4));
        DatasetWriteResult<VersionedDataset> missingDelete = repository.deleteEntry(
                "tenant-a", "quality", "missing", 2, NOW.plusSeconds(5));

        assertThat(metadataWrite).satisfies(result -> {
            assertThat(result.status()).isEqualTo(DatasetWriteStatus.APPLIED);
            assertThat(result.currentVersion()).isEqualTo(1);
        });
        assertThat(staleMetadataWrite).satisfies(result -> {
            assertThat(result.status()).isEqualTo(DatasetWriteStatus.VERSION_CONFLICT);
            assertThat(result.currentVersion()).isEqualTo(1);
        });
        assertThat(entryWrite).satisfies(result -> {
            assertThat(result.status()).isEqualTo(DatasetWriteStatus.APPLIED);
            assertThat(result.currentVersion()).isEqualTo(2);
        });
        assertThat(staleEntryWrite).satisfies(result -> {
            assertThat(result.status()).isEqualTo(DatasetWriteStatus.VERSION_CONFLICT);
            assertThat(result.currentVersion()).isEqualTo(2);
        });
        assertThat(missingDelete.status()).isEqualTo(DatasetWriteStatus.NOT_FOUND);
        assertThat(repository.findDataset("tenant-a", "quality")).get().satisfies(dataset -> {
            assertThat(dataset.version()).isEqualTo(2);
            assertThat(dataset.entryCount()).isOne();
        });
        assertThat(repository.findEntries("tenant-a", "quality"))
                .extracting(value -> value.data().id())
                .containsExactly("row-1");
    }

    @ParameterizedTest(name = "{0} hides another tenant's dataset as not found")
    @MethodSource("repositories")
    void operations_HideCrossTenantResourcesAsNotFound(Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "private", metadata("Private"), NOW);
        repository.putEntry("tenant-a", "private", 0, entry("secret", 0, "classified"), NOW.plusSeconds(1));

        assertThat(repository.findDataset("tenant-b", "private")).isEmpty();
        assertThat(repository.findEntries("tenant-b", "private")).isEmpty();
        assertThat(repository.updateDataset("tenant-b", "private", 1,
                metadata("Probe"), NOW.plusSeconds(2)).status()).isEqualTo(DatasetWriteStatus.NOT_FOUND);
        assertThat(repository.putEntry("tenant-b", "private", 1,
                entry("probe", 0, "probe"), NOW.plusSeconds(2)).status()).isEqualTo(DatasetWriteStatus.NOT_FOUND);
        assertThat(repository.deleteEntry("tenant-b", "private", "secret", 1,
                NOW.plusSeconds(2)).status()).isEqualTo(DatasetWriteStatus.NOT_FOUND);
        assertThat(repository.createSnapshot("tenant-b", "private", 1, "mallory",
                NOW.plusSeconds(2)).status()).isEqualTo(DatasetWriteStatus.NOT_FOUND);
        assertThat(repository.findSnapshot("tenant-b", "private", 1)).isEmpty();
        assertThat(repository.findSnapshots("tenant-b", "private")).isEmpty();
    }

    @ParameterizedTest(name = "{0} keeps snapshots immutable")
    @MethodSource("repositories")
    void snapshots_AreImmutableAfterLaterWrites(Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        repository.putEntry("tenant-a", "quality", 0, entry("row-1", 0, "before"), NOW.plusSeconds(1));

        DatasetSnapshot snapshot = repository.createSnapshot(
                "tenant-a", "quality", 1, "alice", NOW.plusSeconds(2)).value();
        repository.updateDataset("tenant-a", "quality", 1, metadata("Renamed"), NOW.plusSeconds(3));
        repository.putEntry("tenant-a", "quality", 2,
                entry("row-1", 0, "after"), NOW.plusSeconds(4));
        DatasetSnapshot persisted = repository.findSnapshot("tenant-a", "quality", 1).orElseThrow();

        assertThat(snapshot).isEqualTo(persisted);
        assertThat(persisted.metadata().name()).isEqualTo("Quality");
        assertThat(persisted.entries()).singleElement().satisfies(saved -> {
            assertThat(saved.data().input()).isEqualTo("before");
            assertThat(saved.data().labels()).containsExactlyEntriesOf(Map.of("label", "ok"));
        });
        assertThatThrownBy(() -> persisted.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> persisted.entries().getFirst().data().labels().put("label", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0} validates import counts before persistence")
    @MethodSource("repositories")
    void importJobs_ValidateCountsBeforePersistence(Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        repository.createImportJob("tenant-a", "import-1", "quality", "data.jsonl",
                2, 0, "alice", NOW);

        assertAll(
                () -> assertThatThrownBy(() -> repository.updateImportJob(
                        "tenant-a", "import-1", DatasetImportStatus.RUNNING, -1, 0,
                        null, null, NOW.plusSeconds(1), null))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.updateImportJob(
                        "tenant-a", "import-1", DatasetImportStatus.RUNNING, 0, -1,
                        null, null, NOW.plusSeconds(1), null))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.updateImportJob(
                        "tenant-a", "import-1", DatasetImportStatus.RUNNING, 2, 1,
                        null, null, NOW.plusSeconds(1), null))
                        .isInstanceOf(IllegalArgumentException.class));

        assertThat(repository.findImportJob("tenant-a", "import-1")).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo(DatasetImportStatus.PENDING);
            assertThat(job.importedRows()).isZero();
            assertThat(job.failedRows()).isZero();
        });
    }

    @ParameterizedTest(name = "{0} keeps completedAt consistent with terminal status")
    @MethodSource("repositories")
    void importJobs_RequireCompletionTimeExactlyForTerminalStatuses(
            Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        repository.createImportJob("tenant-a", "import-1", "quality", "data.jsonl",
                2, 0, "alice", NOW);

        assertThatThrownBy(() -> repository.updateImportJob(
                "tenant-a", "import-1", DatasetImportStatus.COMPLETED, 2, 0,
                1L, null, NOW.plusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.updateImportJob(
                "tenant-a", "import-1", DatasetImportStatus.RUNNING, 0, 0,
                null, null, NOW.plusSeconds(1), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findImportJob("tenant-a", "import-1")).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo(DatasetImportStatus.PENDING);
            assertThat(job.completedAt()).isNull();
        });
    }

    @ParameterizedTest(name = "{0} does not reopen a terminal import")
    @MethodSource("repositories")
    void importJobs_DoNotTransitionFromTerminalBackToRunning(Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        repository.createImportJob("tenant-a", "import-1", "quality", "data.jsonl",
                2, 0, "alice", NOW);
        Instant completedAt = NOW.plusSeconds(2);
        assertThat(repository.updateImportJob(
                "tenant-a", "import-1", DatasetImportStatus.COMPLETED_WITH_ERRORS, 1, 1,
                1L, "one invalid row", completedAt, completedAt)).isTrue();

        assertThat(repository.updateImportJob(
                "tenant-a", "import-1", DatasetImportStatus.RUNNING, 1, 1,
                null, null, NOW.plusSeconds(3), null)).isFalse();
        assertThat(repository.findImportJob("tenant-a", "import-1")).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo(DatasetImportStatus.COMPLETED_WITH_ERRORS);
            assertThat(job.completedAt()).isEqualTo(completedAt);
            assertThat(job.resultingVersion()).isEqualTo(1L);
        });
    }

    @ParameterizedTest(name = "{0} enforces import error pagination boundaries")
    @MethodSource("repositories")
    void importErrors_EnforceStablePaginationAndBoundaries(Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "quality", metadata("Quality"), NOW);
        repository.createImportJob("tenant-a", "import-1", "quality", "data.jsonl",
                3, 0, "alice", NOW);
        repository.appendImportError("tenant-a", "error-b", "import-1", 1,
                "invalid", "second by id", "{}", NOW.plusSeconds(1));
        repository.appendImportError("tenant-a", "error-c", "import-1", 3,
                "invalid", "third row", "{}", NOW.plusSeconds(2));
        repository.appendImportError("tenant-a", "error-a", "import-1", 1,
                "invalid", "first by id", "{}", NOW.plusSeconds(3));

        assertThat(repository.findImportErrors("tenant-a", "import-1", 2, 1))
                .extracting(DatasetImportError::id)
                .containsExactly("error-b", "error-c");
        assertThat(repository.findImportErrors("tenant-a", "import-1", 1, 3)).isEmpty();
        assertThat(repository.findImportErrors("tenant-a", "import-1", 1000, 0)).hasSize(3);
        assertAll(
                () -> assertThatThrownBy(() -> repository.findImportErrors(
                        "tenant-a", "import-1", 0, 0)).isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.findImportErrors(
                        "tenant-a", "import-1", 1001, 0)).isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.findImportErrors(
                        "tenant-a", "import-1", 1, -1)).isInstanceOf(IllegalArgumentException.class));
    }

    @ParameterizedTest(name = "{0} rejects negative versions at every boundary")
    @MethodSource("repositories")
    void operations_RejectNegativeVersions(Supplier<DatasetVersionRepository> factory) {
        DatasetVersionRepository repository = factory.get();
        repository.createDataset("tenant-a", "quality", metadata("Quality"), NOW);

        assertAll(
                () -> assertThatThrownBy(() -> repository.updateDataset(
                        "tenant-a", "quality", -1, metadata("Invalid"), NOW))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.putEntry(
                        "tenant-a", "quality", -1, entry("row-1", 0, "invalid"), NOW))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.deleteEntry(
                        "tenant-a", "quality", "row-1", -1, NOW))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.createSnapshot(
                        "tenant-a", "quality", -1, "alice", NOW))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.findSnapshot(
                        "tenant-a", "quality", -1))
                        .isInstanceOf(IllegalArgumentException.class),
                () -> assertThatThrownBy(() -> repository.createImportJob(
                        "tenant-a", "import-1", "quality", "data.jsonl", 1, -1, "alice", NOW))
                        .isInstanceOf(IllegalArgumentException.class));
    }

    private static DatasetVersionRepository jdbcRepository() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:xnlp-dataset-contract-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__identity-rbac.sql"),
                portableResource("db/migration/V11__dataset-versioning.sql"))
                .execute(dataSource);
        return new JdbcDatasetVersionRepository(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    private static Resource portableResource(String location) {
        try {
            String sql = StreamUtils.copyToString(new ClassPathResource(location).getInputStream(),
                    StandardCharsets.UTF_8);
            return new org.springframework.core.io.ByteArrayResource(
                    sql.replace("__LARGE_TEXT__", "LONGTEXT").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DatasetMetadata metadata(String name) {
        return new DatasetMetadata(name, "Contract dataset", "TEXT_CLASSIFICATION");
    }

    private static DatasetEntryData entry(String id, int sequence, String input) {
        return new DatasetEntryData(id, sequence, input, "expected",
                Map.of("label", "ok"), Map.of("source", "contract"));
    }
}
