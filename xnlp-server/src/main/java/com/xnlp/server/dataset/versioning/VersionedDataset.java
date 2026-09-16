package com.xnlp.server.dataset.versioning;

import java.time.Instant;
import java.util.Objects;

public record VersionedDataset(
        String tenantId,
        String id,
        DatasetMetadata metadata,
        long version,
        int entryCount,
        Instant createdAt,
        Instant updatedAt) {

    public VersionedDataset {
        tenantId = DatasetVersioningSupport.tenantId(tenantId);
        id = DatasetVersioningSupport.text(id, "id", 64);
        Objects.requireNonNull(metadata, "metadata");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (entryCount < 0) throw new IllegalArgumentException("entryCount must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
