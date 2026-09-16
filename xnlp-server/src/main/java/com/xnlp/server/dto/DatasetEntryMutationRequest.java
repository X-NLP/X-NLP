package com.xnlp.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Optimistic-lock request for creating or replacing one Dataset entry. */
public record DatasetEntryMutationRequest(
        @Size(max = 64) String id,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @PositiveOrZero Integer sequence,
        @NotBlank String input,
        String expectedOutput,
        Map<String, Object> labels,
        Map<String, Object> metadata) {

    public DatasetEntryMutationRequest {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
