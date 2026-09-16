package com.xnlp.server.dataset.versioning;

import java.time.Instant;
import java.util.Objects;

public record DatasetImportJob(
        String tenantId,
        String id,
        String datasetId,
        String sourceName,
        DatasetImportStatus status,
        int totalRows,
        int importedRows,
        int failedRows,
        long expectedVersion,
        Long resultingVersion,
        String errorSummary,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public DatasetImportJob {
        tenantId = DatasetVersioningSupport.tenantId(tenantId);
        id = DatasetVersioningSupport.text(id, "id", 64);
        datasetId = DatasetVersioningSupport.text(datasetId, "datasetId", 64);
        sourceName = DatasetVersioningSupport.text(sourceName, "sourceName", 512);
        Objects.requireNonNull(status, "status");
        if (totalRows < 0 || importedRows < 0 || failedRows < 0) {
            throw new IllegalArgumentException("Import row counts must not be negative");
        }
        if (importedRows + failedRows > totalRows) {
            throw new IllegalArgumentException("Processed rows must not exceed totalRows");
        }
        if (expectedVersion < 0 || resultingVersion != null && resultingVersion < 0) {
            throw new IllegalArgumentException("Import versions must not be negative");
        }
        errorSummary = DatasetVersioningSupport.nullableText(errorSummary, 65535);
        createdBy = DatasetVersioningSupport.text(createdBy, "createdBy", 190);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (status.terminal() && completedAt == null) {
            throw new IllegalArgumentException("A terminal import job requires completedAt");
        }
        if (!status.terminal() && completedAt != null) {
            throw new IllegalArgumentException("A non-terminal import job must not have completedAt");
        }
    }
}
