package com.xnlp.server.quota;

public record QuotaLimits(
        long requestsPerMinute,
        long modelCallsPerMinute,
        long knowledgeImportsPerHour,
        int concurrentRequests) {

    public QuotaLimits {
        requireNonNegative(requestsPerMinute, "requestsPerMinute");
        requireNonNegative(modelCallsPerMinute, "modelCallsPerMinute");
        requireNonNegative(knowledgeImportsPerHour, "knowledgeImportsPerHour");
        requireNonNegative(concurrentRequests, "concurrentRequests");
    }

    public long limitFor(QuotaDimension dimension) {
        return switch (dimension) {
            case REQUEST -> requestsPerMinute;
            case MODEL_CALL -> modelCallsPerMinute;
            case KNOWLEDGE_IMPORT -> knowledgeImportsPerHour;
        };
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }
}
