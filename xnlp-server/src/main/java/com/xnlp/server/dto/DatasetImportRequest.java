package com.xnlp.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DatasetImportRequest(
        @NotBlank @Size(max = 512) String sourceName,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @Size(max = 10_000) List<DatasetImportEntryRequest> entries) {

    public DatasetImportRequest {
        entries = entries == null ? null : List.copyOf(entries);
    }
}
