package com.xnlp.server.dto;

import com.xnlp.server.dataset.versioning.VersionedDatasetEntry;

import java.time.Instant;
import java.util.Map;

public record DatasetEntryMutationResponse(
        String tenantId,
        String datasetId,
        String id,
        int sequence,
        String input,
        String expectedOutput,
        Map<String, Object> labels,
        Map<String, Object> metadata,
        long datasetVersion,
        Instant createdAt,
        Instant updatedAt) {

    public static DatasetEntryMutationResponse from(VersionedDatasetEntry entry, long datasetVersion) {
        return new DatasetEntryMutationResponse(
                entry.tenantId(), entry.datasetId(), entry.data().id(), entry.data().sequence(),
                entry.data().input(), entry.data().expectedOutput(), entry.data().labels(), entry.data().metadata(),
                datasetVersion, entry.createdAt(), entry.updatedAt());
    }
}
