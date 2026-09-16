package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Durable progress contract for asynchronous document indexing. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestionJob(
        String id,
        String knowledgeBaseId,
        Status status,
        int totalDocuments,
        int processedDocuments,
        int failedDocuments,
        String errorSummary,
        Instant createdAt,
        Instant completedAt) {

    public IngestionJob {
        id = requireText(id, "id");
        knowledgeBaseId = requireText(knowledgeBaseId, "knowledgeBaseId");
        status = status == null ? Status.PENDING : status;
        if (totalDocuments < 0 || processedDocuments < 0 || failedDocuments < 0) {
            throw new IllegalArgumentException("ingestion counters must not be negative");
        }
        if (processedDocuments > totalDocuments || failedDocuments > processedDocuments) {
            throw new IllegalArgumentException("ingestion counters are inconsistent");
        }
    }

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
