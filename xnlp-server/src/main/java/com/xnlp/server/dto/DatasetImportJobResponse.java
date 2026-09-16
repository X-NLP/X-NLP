package com.xnlp.server.dto;

import com.xnlp.server.dataset.versioning.DatasetImportJob;
import com.xnlp.server.dataset.versioning.DatasetImportStatus;

import java.time.Instant;

public record DatasetImportJobResponse(
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

    public static DatasetImportJobResponse from(DatasetImportJob job) {
        return new DatasetImportJobResponse(
                job.id(), job.datasetId(), job.sourceName(), job.status(), job.totalRows(),
                job.importedRows(), job.failedRows(), job.expectedVersion(), job.resultingVersion(),
                job.errorSummary(), job.createdBy(), job.createdAt(), job.updatedAt(), job.completedAt());
    }
}
