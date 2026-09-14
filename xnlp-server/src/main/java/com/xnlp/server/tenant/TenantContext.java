package com.xnlp.server.tenant;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Request-scoped tenant identity used by persistence adapters and background
 * work. The context is deliberately thread-local: servlet requests and
 * evaluation workers must never share a tenant accidentally.
 */
public final class TenantContext {

    public static final String DEFAULT_TENANT_ID = "default";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String currentTenantId() {
        return CURRENT.get() == null ? DEFAULT_TENANT_ID : CURRENT.get();
    }

    public static void setTenantId(String tenantId) {
        CURRENT.set(normalize(tenantId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void runWithTenant(String tenantId, Runnable action) {
        Objects.requireNonNull(action, "action");
        String previous = CURRENT.get();
        setTenantId(tenantId);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T callWithTenant(String tenantId, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        String previous = CURRENT.get();
        setTenantId(tenantId);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** Validate IDs before they reach SQL values, filesystem paths or logs. */
    public static String normalize(String tenantId) {
        String value = tenantId == null || tenantId.isBlank()
                ? DEFAULT_TENANT_ID : tenantId.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid tenant id: " + tenantId);
        }
        return value;
    }

    private static void restore(String previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
