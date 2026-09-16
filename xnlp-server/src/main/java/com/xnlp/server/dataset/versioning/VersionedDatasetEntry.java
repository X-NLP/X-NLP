package com.xnlp.server.dataset.versioning;

import java.time.Instant;
import java.util.Objects;

public record VersionedDatasetEntry(
        String tenantId,
        String datasetId,
        DatasetEntryData data,
        Instant createdAt,
        Instant updatedAt) {

    public VersionedDatasetEntry {
        tenantId = DatasetVersioningSupport.tenantId(tenantId);
        datasetId = DatasetVersioningSupport.text(datasetId, "datasetId", 64);
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
