package com.xnlp.server.dto;

import com.xnlp.server.quota.QuotaLimits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record QuotaUpdateRequest(
        @NotNull(message = "requestsPerMinute is required")
        @PositiveOrZero(message = "requestsPerMinute must not be negative")
        Long requestsPerMinute,
        @NotNull(message = "modelCallsPerMinute is required")
        @PositiveOrZero(message = "modelCallsPerMinute must not be negative")
        Long modelCallsPerMinute,
        @NotNull(message = "knowledgeImportsPerHour is required")
        @PositiveOrZero(message = "knowledgeImportsPerHour must not be negative")
        Long knowledgeImportsPerHour,
        @NotNull(message = "concurrentRequests is required")
        @PositiveOrZero(message = "concurrentRequests must not be negative")
        Integer concurrentRequests) {

    public QuotaLimits toLimits() {
        if (requestsPerMinute == null || modelCallsPerMinute == null
                || knowledgeImportsPerHour == null || concurrentRequests == null) {
            throw new IllegalArgumentException("all quota limits are required");
        }
        return new QuotaLimits(
                requestsPerMinute,
                modelCallsPerMinute,
                knowledgeImportsPerHour,
                concurrentRequests);
    }
}
