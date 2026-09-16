package com.xnlp.server.quota;

import com.xnlp.server.tenant.TenantContext;

import java.time.Instant;
import java.util.Objects;

public record QuotaUsageWindow(
        String tenantId,
        QuotaDimension dimension,
        Instant windowStart,
        Instant windowEnd,
        long usedUnits,
        long limitUnits,
        Instant updatedAt) {

    public QuotaUsageWindow {
        tenantId = TenantContext.normalize(tenantId);
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!windowEnd.isAfter(windowStart)) throw new IllegalArgumentException("windowEnd must be after windowStart");
        if (usedUnits < 0 || limitUnits < 0) throw new IllegalArgumentException("usage and limit must not be negative");
    }
}
