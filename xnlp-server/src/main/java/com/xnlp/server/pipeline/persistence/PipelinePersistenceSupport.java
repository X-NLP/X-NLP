package com.xnlp.server.pipeline.persistence;

import java.time.Instant;

final class PipelinePersistenceSupport {

    private PipelinePersistenceSupport() {
    }

    static String text(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    static String nullableText(String value, String field, int maximumLength) {
        if (value == null) return null;
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maximumLength + " characters");
        }
        return value;
    }

    static Instant instant(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must not be null");
        return value;
    }
}
