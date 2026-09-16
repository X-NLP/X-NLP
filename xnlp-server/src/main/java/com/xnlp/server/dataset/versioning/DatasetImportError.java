package com.xnlp.server.dataset.versioning;

import java.time.Instant;
import java.util.Objects;

public record DatasetImportError(
        String tenantId,
        String id,
        String jobId,
        int rowNumber,
        String errorCode,
        String message,
        String rawRecord,
        Instant createdAt) {

    public DatasetImportError {
        tenantId = DatasetVersioningSupport.tenantId(tenantId);
        id = DatasetVersioningSupport.text(id, "id", 64);
        jobId = DatasetVersioningSupport.text(jobId, "jobId", 64);
        if (rowNumber < 1) throw new IllegalArgumentException("rowNumber must be positive");
        errorCode = DatasetVersioningSupport.text(errorCode, "errorCode", 96);
        message = DatasetVersioningSupport.text(message, "message", 65535);
        rawRecord = DatasetVersioningSupport.nullableText(rawRecord, Integer.MAX_VALUE);
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
