package com.xnlp.server.security;

import java.util.Locale;

public enum TenantRole {
    ADMIN,
    DEVELOPER,
    VIEWER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static TenantRole parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tenant role must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring(5);
        }
        return TenantRole.valueOf(normalized);
    }
}
