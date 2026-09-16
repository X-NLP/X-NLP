package com.xnlp.server.dto;

import com.xnlp.server.dataset.versioning.DatasetImportError;

import java.time.Instant;

public record DatasetImportErrorResponse(
        String id,
        int rowNumber,
        String errorCode,
        String message,
        String rawRecord,
        Instant createdAt) {

    public static DatasetImportErrorResponse from(DatasetImportError error) {
        return new DatasetImportErrorResponse(
                error.id(), error.rowNumber(), error.errorCode(), error.message(),
                error.rawRecord(), error.createdAt());
    }
}
