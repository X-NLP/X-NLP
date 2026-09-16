package com.xnlp.server.dataset.versioning;

import com.xnlp.server.tenant.TenantContext;

import java.util.Map;

final class DatasetVersioningSupport {

    private DatasetVersioningSupport() {
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

    static String nullableText(String value, int maximumLength) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("value must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    static Map<String, Object> map(Map<String, Object> value) {
        return value == null || value.isEmpty() ? Map.of() : Map.copyOf(value);
    }
}
