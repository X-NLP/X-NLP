package com.xnlp.server.dataset.versioning;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DatasetVersionRepository {

    Optional<VersionedDataset> findDataset(String tenantId, String datasetId);

    VersionedDataset createDataset(String tenantId, String datasetId, DatasetMetadata metadata, Instant now);

    /**
     * Creates version zero with all initial entries in one atomic operation. If the tenant-scoped
     * dataset already exists, the existing version is returned without changing it.
     */
    VersionedDataset bootstrapDataset(
            String tenantId, String datasetId, DatasetMetadata metadata, List<DatasetEntryData> entries,
            String createdBy, Instant createdAt, Instant updatedAt);

    DatasetWriteResult<VersionedDataset> updateDataset(
            String tenantId, String datasetId, long expectedVersion, DatasetMetadata metadata, Instant now);

    List<VersionedDatasetEntry> findEntries(String tenantId, String datasetId);

    DatasetWriteResult<VersionedDatasetEntry> putEntry(
            String tenantId, String datasetId, long expectedVersion, DatasetEntryData entry, Instant now);

    DatasetWriteResult<VersionedDataset> deleteEntry(
            String tenantId, String datasetId, String entryId, long expectedVersion, Instant now);

    DatasetWriteResult<DatasetSnapshot> createSnapshot(
            String tenantId, String datasetId, long expectedVersion, String createdBy, Instant now);

    Optional<DatasetSnapshot> findSnapshot(String tenantId, String datasetId, long version);

    List<DatasetSnapshot> findSnapshots(String tenantId, String datasetId);

    DatasetImportJob createImportJob(String tenantId, String jobId, String datasetId, String sourceName,
                                     int totalRows, long expectedVersion, String createdBy, Instant now);

    Optional<DatasetImportJob> findImportJob(String tenantId, String jobId);

    boolean updateImportJob(String tenantId, String jobId, DatasetImportStatus status, int importedRows,
                            int failedRows, Long resultingVersion, String errorSummary,
                            Instant updatedAt, Instant completedAt);

    DatasetImportError appendImportError(String tenantId, String errorId, String jobId, int rowNumber,
                                         String errorCode, String message, String rawRecord, Instant now);

    List<DatasetImportError> findImportErrors(String tenantId, String jobId, int limit, int offset);
}
