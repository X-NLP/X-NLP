package com.xnlp.server.dto;

import java.util.List;

public record DatasetImportResponse(
        DatasetImportJobResponse job,
        List<DatasetImportErrorResponse> errors) {

    public DatasetImportResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
