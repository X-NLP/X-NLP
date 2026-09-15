package com.xnlp.server.dto;

import com.xnlp.core.rag.KnowledgeDocument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Validated text-first document ingestion contract. */
public record KnowledgeDocumentCreateRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 500, message = "title must not exceed 500 characters")
        String title,
        @NotBlank(message = "content must not be blank")
        @Size(max = 2_000_000, message = "content must not exceed 2000000 characters")
        String content,
        @NotNull(message = "sourceType must be specified")
        KnowledgeDocument.SourceType sourceType,
        @Size(max = 2_048, message = "sourceUri must not exceed 2048 characters")
        String sourceUri,
        @Size(max = 190, message = "externalId must not exceed 190 characters")
        String externalId,
        @Size(max = 100, message = "metadata must not contain more than 100 values")
        Map<String, Object> metadata) {
}
