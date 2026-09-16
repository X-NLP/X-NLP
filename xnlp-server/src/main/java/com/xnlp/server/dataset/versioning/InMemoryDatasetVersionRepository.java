package com.xnlp.server.dataset.versioning;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile("memory")
public class InMemoryDatasetVersionRepository implements DatasetVersionRepository {

    private final Map<DatasetKey, VersionedDataset> datasets = new HashMap<>();
    private final Map<DatasetKey, Map<String, VersionedDatasetEntry>> entries = new HashMap<>();
    private final Map<SnapshotKey, DatasetSnapshot> snapshots = new HashMap<>();
    private final Map<JobKey, DatasetImportJob> jobs = new HashMap<>();
    private final Map<JobKey, List<DatasetImportError>> errors = new HashMap<>();

    @Override
    public synchronized Optional<VersionedDataset> findDataset(String tenantId, String datasetId) {
        return Optional.ofNullable(datasets.get(datasetKey(tenantId, datasetId)));
    }

    @Override
    public synchronized VersionedDataset createDataset(
            String tenantId, String datasetId, DatasetMetadata metadata, Instant now) {
        DatasetKey key = datasetKey(tenantId, datasetId);
        if (datasets.containsKey(key)) throw new IllegalStateException("Dataset already exists: " + datasetId);
        VersionedDataset value = new VersionedDataset(key.tenantId(), key.datasetId(), metadata, 0, 0, now, now);
        datasets.put(key, value);
        entries.put(key, new LinkedHashMap<>());
        return value;
    }

    @Override
    public synchronized VersionedDataset bootstrapDataset(
            String tenantId, String datasetId, DatasetMetadata metadata, List<DatasetEntryData> initialEntries,
            String createdBy, Instant createdAt, Instant updatedAt) {
        DatasetKey key = datasetKey(tenantId, datasetId);
        VersionedDataset existing = datasets.get(key);
        if (existing != null) return existing;
        String actor = DatasetVersioningSupport.text(createdBy, "createdBy", 190);
        Map<String, VersionedDatasetEntry> storedEntries = new LinkedHashMap<>();
        for (DatasetEntryData entry : List.copyOf(initialEntries)) {
            if (storedEntries.putIfAbsent(entry.id(), new VersionedDatasetEntry(
                    key.tenantId(), key.datasetId(), entry, createdAt, updatedAt)) != null) {
                throw new IllegalArgumentException("Duplicate dataset entry id: " + entry.id());
            }
        }
        VersionedDataset created = new VersionedDataset(key.tenantId(), key.datasetId(), metadata, 0,
                storedEntries.size(), createdAt, updatedAt);
        DatasetSnapshot initialSnapshot = new DatasetSnapshot(key.tenantId(), key.datasetId(), 0, metadata,
                orderedEntries(storedEntries), actor, updatedAt);
        datasets.put(key, created);
        entries.put(key, storedEntries);
        snapshots.put(new SnapshotKey(key, 0), initialSnapshot);
        return created;
    }

    @Override
    public synchronized DatasetWriteResult<VersionedDataset> updateDataset(
            String tenantId, String datasetId, long expectedVersion, DatasetMetadata metadata, Instant now) {
        validateVersion(expectedVersion);
        DatasetKey key = datasetKey(tenantId, datasetId);
        VersionedDataset current = datasets.get(key);
        if (current == null) return DatasetWriteResult.notFound();
        if (current.version() != expectedVersion) return DatasetWriteResult.conflict(current.version());
        VersionedDataset updated = new VersionedDataset(key.tenantId(), key.datasetId(), metadata,
                current.version() + 1, current.entryCount(), current.createdAt(), now);
        datasets.put(key, updated);
        return DatasetWriteResult.applied(updated, updated.version());
    }

    @Override
    public synchronized List<VersionedDatasetEntry> findEntries(String tenantId, String datasetId) {
        Map<String, VersionedDatasetEntry> values = entries.get(datasetKey(tenantId, datasetId));
        if (values == null) return List.of();
        return orderedEntries(values);
    }

    @Override
    public synchronized DatasetWriteResult<VersionedDatasetEntry> putEntry(
            String tenantId, String datasetId, long expectedVersion, DatasetEntryData entry, Instant now) {
        validateVersion(expectedVersion);
        DatasetKey key = datasetKey(tenantId, datasetId);
        VersionedDataset current = datasets.get(key);
        if (current == null) return DatasetWriteResult.notFound();
        if (current.version() != expectedVersion) return DatasetWriteResult.conflict(current.version());
        Map<String, VersionedDatasetEntry> values = entries.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        VersionedDatasetEntry existing = values.get(entry.id());
        VersionedDatasetEntry saved = new VersionedDatasetEntry(key.tenantId(), key.datasetId(), entry,
                existing == null ? now : existing.createdAt(), now);
        values.put(entry.id(), saved);
        long newVersion = current.version() + 1;
        datasets.put(key, new VersionedDataset(key.tenantId(), key.datasetId(), current.metadata(), newVersion,
                values.size(), current.createdAt(), now));
        return DatasetWriteResult.applied(saved, newVersion);
    }

    @Override
    public synchronized DatasetWriteResult<VersionedDataset> deleteEntry(
            String tenantId, String datasetId, String entryId, long expectedVersion, Instant now) {
        validateVersion(expectedVersion);
        DatasetKey key = datasetKey(tenantId, datasetId);
        VersionedDataset current = datasets.get(key);
        if (current == null) return DatasetWriteResult.notFound();
        if (current.version() != expectedVersion) return DatasetWriteResult.conflict(current.version());
        Map<String, VersionedDatasetEntry> values = entries.getOrDefault(key, Map.of());
        if (!values.containsKey(normalizeId(entryId, "entryId"))) return DatasetWriteResult.notFound();
        values.remove(entryId.trim());
        VersionedDataset updated = new VersionedDataset(key.tenantId(), key.datasetId(), current.metadata(),
                current.version() + 1, values.size(), current.createdAt(), now);
        datasets.put(key, updated);
        return DatasetWriteResult.applied(updated, updated.version());
    }

    @Override
    public synchronized DatasetWriteResult<DatasetSnapshot> createSnapshot(
            String tenantId, String datasetId, long expectedVersion, String createdBy, Instant now) {
        validateVersion(expectedVersion);
        DatasetKey key = datasetKey(tenantId, datasetId);
        VersionedDataset current = datasets.get(key);
        if (current == null) return DatasetWriteResult.notFound();
        if (current.version() != expectedVersion) return DatasetWriteResult.conflict(current.version());
        SnapshotKey snapshotKey = new SnapshotKey(key, expectedVersion);
        DatasetSnapshot snapshot = snapshots.computeIfAbsent(snapshotKey, ignored -> new DatasetSnapshot(
                key.tenantId(), key.datasetId(), expectedVersion, current.metadata(), findEntries(tenantId, datasetId),
                createdBy, now));
        return DatasetWriteResult.applied(snapshot, current.version());
    }

    @Override
    public synchronized Optional<DatasetSnapshot> findSnapshot(String tenantId, String datasetId, long version) {
        validateVersion(version);
        return Optional.ofNullable(snapshots.get(new SnapshotKey(datasetKey(tenantId, datasetId), version)));
    }

    @Override
    public synchronized List<DatasetSnapshot> findSnapshots(String tenantId, String datasetId) {
        DatasetKey key = datasetKey(tenantId, datasetId);
        return snapshots.entrySet().stream().filter(entry -> entry.getKey().dataset().equals(key))
                .map(Map.Entry::getValue).sorted(Comparator.comparingLong(DatasetSnapshot::version).reversed()).toList();
    }

    @Override
    public synchronized DatasetImportJob createImportJob(
            String tenantId, String jobId, String datasetId, String sourceName,
            int totalRows, long expectedVersion, String createdBy, Instant now) {
        JobKey key = jobKey(tenantId, jobId);
        if (jobs.containsKey(key)) throw new IllegalStateException("Import job already exists: " + jobId);
        DatasetImportJob job = new DatasetImportJob(key.tenantId(), key.jobId(), normalizeId(datasetId, "datasetId"),
                sourceName, DatasetImportStatus.PENDING, totalRows, 0, 0, expectedVersion, null,
                null, createdBy, now, now, null);
        jobs.put(key, job);
        return job;
    }

    @Override
    public synchronized Optional<DatasetImportJob> findImportJob(String tenantId, String jobId) {
        return Optional.ofNullable(jobs.get(jobKey(tenantId, jobId)));
    }

    @Override
    public synchronized boolean updateImportJob(
            String tenantId, String jobId, DatasetImportStatus status, int importedRows, int failedRows,
            Long resultingVersion, String errorSummary, Instant updatedAt, Instant completedAt) {
        JobKey key = jobKey(tenantId, jobId);
        DatasetImportJob current = jobs.get(key);
        if (current == null || current.status().terminal()) return false;
        jobs.put(key, new DatasetImportJob(key.tenantId(), key.jobId(), current.datasetId(), current.sourceName(),
                status, current.totalRows(), importedRows, failedRows, current.expectedVersion(), resultingVersion,
                errorSummary, current.createdBy(), current.createdAt(), updatedAt, completedAt));
        return true;
    }

    @Override
    public synchronized DatasetImportError appendImportError(
            String tenantId, String errorId, String jobId, int rowNumber, String errorCode,
            String message, String rawRecord, Instant now) {
        JobKey key = jobKey(tenantId, jobId);
        if (!jobs.containsKey(key)) throw new IllegalStateException("Import job not found: " + jobId);
        DatasetImportError error = new DatasetImportError(key.tenantId(), normalizeId(errorId, "errorId"),
                key.jobId(), rowNumber, errorCode, message, rawRecord, now);
        List<DatasetImportError> values = errors.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (values.stream().anyMatch(existing -> existing.id().equals(error.id()))) {
            throw new IllegalStateException("Import error already exists: " + errorId);
        }
        values.add(error);
        return error;
    }

    @Override
    public synchronized List<DatasetImportError> findImportErrors(
            String tenantId, String jobId, int limit, int offset) {
        validatePage(limit, offset);
        List<DatasetImportError> values = errors.getOrDefault(jobKey(tenantId, jobId), List.of()).stream()
                .sorted(Comparator.comparingInt(DatasetImportError::rowNumber).thenComparing(DatasetImportError::id))
                .toList();
        if (offset >= values.size()) return List.of();
        return values.subList(offset, Math.min(values.size(), offset + limit));
    }

    private static List<VersionedDatasetEntry> orderedEntries(Map<String, VersionedDatasetEntry> values) {
        return values.values().stream().sorted(Comparator
                .comparingInt((VersionedDatasetEntry value) -> value.data().sequence())
                .thenComparing(value -> value.data().id())).toList();
    }

    private static DatasetKey datasetKey(String tenantId, String datasetId) {
        return new DatasetKey(DatasetVersioningSupport.tenantId(tenantId), normalizeId(datasetId, "datasetId"));
    }

    private static JobKey jobKey(String tenantId, String jobId) {
        return new JobKey(DatasetVersioningSupport.tenantId(tenantId), normalizeId(jobId, "jobId"));
    }

    private static String normalizeId(String value, String field) {
        return DatasetVersioningSupport.text(value, field, 64);
    }

    private static void validateVersion(long version) {
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0) throw new IllegalArgumentException("Invalid pagination");
    }

    private record DatasetKey(String tenantId, String datasetId) { }
    private record SnapshotKey(DatasetKey dataset, long version) { }
    private record JobKey(String tenantId, String jobId) { }
}
