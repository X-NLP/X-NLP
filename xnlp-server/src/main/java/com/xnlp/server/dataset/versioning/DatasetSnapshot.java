package com.xnlp.server.dataset.versioning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DatasetSnapshot(
        String tenantId,
        String datasetId,
        long version,
        DatasetMetadata metadata,
        List<VersionedDatasetEntry> entries,
        String createdBy,
        Instant createdAt) {

    public DatasetSnapshot {
        tenantId = DatasetVersioningSupport.tenantId(tenantId);
        datasetId = DatasetVersioningSupport.text(datasetId, "datasetId", 64);
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(metadata, "metadata");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        createdBy = DatasetVersioningSupport.text(createdBy, "createdBy", 190);
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
