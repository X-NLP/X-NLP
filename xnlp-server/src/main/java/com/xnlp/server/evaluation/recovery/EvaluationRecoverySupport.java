package com.xnlp.server.evaluation.recovery;

import com.xnlp.server.tenant.TenantContext;

import java.time.Instant;
import java.util.Objects;

final class EvaluationRecoverySupport {

    private EvaluationRecoverySupport() {
    }

    static String tenantId(String value) {
        return TenantContext.normalize(value);
    }

    static String text(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    static String nullableText(String value, String field, int maximumLength) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    static Instant instant(Instant value, String field) {
        return Objects.requireNonNull(value, field);
    }
}
