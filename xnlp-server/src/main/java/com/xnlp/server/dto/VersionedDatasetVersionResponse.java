package com.xnlp.server.dto;

import com.xnlp.server.dataset.versioning.DatasetSnapshot;

import java.time.Instant;

public record VersionedDatasetVersionResponse(
        String datasetId,
        long version,
        String name,
        String description,
        String taskType,
        int entryCount,
        String createdBy,
        Instant createdAt) {

    public static VersionedDatasetVersionResponse from(DatasetSnapshot snapshot) {
        return new VersionedDatasetVersionResponse(
                snapshot.datasetId(), snapshot.version(), snapshot.metadata().name(),
                snapshot.metadata().description(), snapshot.metadata().taskType(), snapshot.entries().size(),
                snapshot.createdBy(), snapshot.createdAt());
    }
}
