package com.xnlp.server.quota;

import com.xnlp.server.dto.QuotaResponse;
import com.xnlp.server.dto.QuotaUpdateRequest;
import com.xnlp.server.security.TenantAuthorizationService;
import com.xnlp.server.security.TenantRole;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class TenantQuotaService {

    private static final QuotaLimits ZERO_LIMITS = new QuotaLimits(0, 0, 0, 0);

    private final TenantQuotaRepository quotas;
    private final TenantAuthorizationService authorization;
    private final Clock clock;

    @Autowired
    public TenantQuotaService(TenantQuotaRepository quotas, TenantAuthorizationService authorization) {
        this(quotas, authorization, Clock.systemUTC());
    }

    TenantQuotaService(TenantQuotaRepository quotas, TenantAuthorizationService authorization, Clock clock) {
        this.quotas = quotas;
        this.authorization = authorization;
        this.clock = clock;
    }

    public QuotaResponse get(String tenantId) {
        authorization.require(tenantId, TenantRole.ADMIN);
        Instant now = clock.instant();
        TenantQuota quota = quotas.findLimits(tenantId).orElse(null);
        QuotaLimits limits = quota == null ? ZERO_LIMITS : quota.limits();
        return QuotaResponse.from(
                tenantId,
                limits,
                usage(tenantId, QuotaDimension.REQUEST, now.truncatedTo(ChronoUnit.MINUTES)),
                usage(tenantId, QuotaDimension.MODEL_CALL, now.truncatedTo(ChronoUnit.MINUTES)),
                usage(tenantId, QuotaDimension.KNOWLEDGE_IMPORT, now.truncatedTo(ChronoUnit.HOURS)),
                quotas.countActiveLeases(tenantId, now),
                quota == null ? null : quota.createdAt(),
                quota == null ? null : quota.updatedAt());
    }

    public QuotaResponse update(String tenantId, QuotaUpdateRequest request) {
        authorization.require(tenantId, TenantRole.ADMIN);
        if (request == null) throw new IllegalArgumentException("quota request is required");
        quotas.saveLimits(tenantId, request.toLimits());
        return get(tenantId);
    }

    private long usage(String tenantId, QuotaDimension dimension, Instant windowStart) {
        return quotas.findUsageWindow(tenantId, dimension, windowStart)
                .map(QuotaUsageWindow::usedUnits)
                .orElse(0L);
    }
}
