package com.xnlp.server.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Optimistic document update that triggers reindex when content changes. */
public record KnowledgeDocumentUpdateRequest(
        @Size(min = 1, max = 500, message = "title must contain between 1 and 500 characters")
        String title,
        @Size(min = 1, max = 2_000_000, message = "content must contain between 1 and 2000000 characters")
        String content,
        @Size(max = 100, message = "metadata must not contain more than 100 values")
        Map<String, Object> metadata,
        @NotNull(message = "expectedVersion must be specified")
        @Min(value = 1, message = "expectedVersion must be at least 1")
        Long expectedVersion) {

    @AssertTrue(message = "at least one update field must be supplied")
    public boolean isUpdatePresent() {
        return title != null || content != null || metadata != null;
    }
}
