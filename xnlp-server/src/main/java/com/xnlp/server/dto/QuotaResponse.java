package com.xnlp.server.dto;

import com.xnlp.server.quota.QuotaLimits;
import com.xnlp.server.tenant.TenantContext;

import java.time.Instant;

public record QuotaResponse(
        String tenantId,
        Limits limits,
        Usage currentUsage,
        Instant createdAt,
        Instant updatedAt) {

    public static QuotaResponse from(
            String tenantId,
            QuotaLimits limits,
            long requests,
            long modelCalls,
            long knowledgeImports,
            long concurrentRequests,
            Instant createdAt,
            Instant updatedAt) {
        return new QuotaResponse(
                TenantContext.normalize(tenantId),
                new Limits(
                        limits.requestsPerMinute(),
                        limits.modelCallsPerMinute(),
                        limits.knowledgeImportsPerHour(),
                        limits.concurrentRequests()),
                new Usage(requests, modelCalls, knowledgeImports, concurrentRequests),
                createdAt,
                updatedAt);
    }

    public record Limits(
            long requestsPerMinute,
            long modelCallsPerMinute,
            long knowledgeImportsPerHour,
            int concurrentRequests) {
    }

    public record Usage(
            long requestsInCurrentMinute,
            long modelCallsInCurrentMinute,
            long knowledgeImportsInCurrentHour,
            long concurrentRequests) {
    }
}
