package com.xnlp.server.quota;

import com.xnlp.server.tenant.TenantContext;

import java.time.Instant;
import java.util.Objects;

public record ConcurrencyLease(
        String tenantId,
        String leaseId,
        int slot,
        String ownerId,
        Instant acquiredAt,
        Instant expiresAt) {

    public ConcurrencyLease {
        tenantId = TenantContext.normalize(tenantId);
        leaseId = requireText(leaseId, "leaseId");
        ownerId = requireText(ownerId, "ownerId");
        if (slot < 0) throw new IllegalArgumentException("slot must not be negative");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(acquiredAt)) throw new IllegalArgumentException("expiresAt must be after acquiredAt");
    }

    public boolean activeAt(Instant instant) {
        return expiresAt.isAfter(Objects.requireNonNull(instant, "instant"));
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
