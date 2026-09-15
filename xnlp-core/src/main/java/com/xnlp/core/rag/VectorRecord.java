package com.xnlp.core.rag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistable embedding bound to a chunk, model and content checksum. */
public record VectorRecord(
        String chunkId,
        String knowledgeBaseId,
        String documentId,
        String embeddingModel,
        int dimensions,
        float[] embedding,
        String contentChecksum,
        Map<String, Object> metadata,
        Instant updatedAt) {

    public VectorRecord {
        chunkId = requireText(chunkId, "chunkId");
        knowledgeBaseId = requireText(knowledgeBaseId, "knowledgeBaseId");
        documentId = requireText(documentId, "documentId");
        embeddingModel = requireText(embeddingModel, "embeddingModel");
        contentChecksum = requireText(contentChecksum, "contentChecksum");
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding must not be empty");
        }
        if (dimensions != embedding.length) {
            throw new IllegalArgumentException("dimensions must match embedding length");
        }
        validateFinite(embedding, "embedding");
        embedding = embedding.clone();
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    private static void validateFinite(float[] values, String field) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(field + " must contain only finite values");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
