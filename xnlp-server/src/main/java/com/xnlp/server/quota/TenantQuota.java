package com.xnlp.server.quota;

import com.xnlp.server.tenant.TenantContext;

import java.time.Instant;
import java.util.Objects;

public record TenantQuota(
        String tenantId,
        QuotaLimits limits,
        Instant createdAt,
        Instant updatedAt) {

    public TenantQuota {
        tenantId = TenantContext.normalize(tenantId);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
