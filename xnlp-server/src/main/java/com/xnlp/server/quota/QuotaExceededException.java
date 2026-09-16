package com.xnlp.server.quota;

/** Raised when a tenant request cannot claim rate or concurrency capacity. */
public final class QuotaExceededException extends RuntimeException {

    public static final String RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";
    public static final String CONCURRENCY_LIMIT_EXCEEDED = "concurrency_limit_exceeded";

    private final String code;
    private final long retryAfterSeconds;

    private QuotaExceededException(String code, String message, long retryAfterSeconds) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        if (retryAfterSeconds < 1) throw new IllegalArgumentException("retryAfterSeconds must be positive");
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static QuotaExceededException rateLimitExceeded(long retryAfterSeconds) {
        return new QuotaExceededException(
                RATE_LIMIT_EXCEEDED,
                "Tenant request rate limit exceeded",
                retryAfterSeconds);
    }

    public static QuotaExceededException concurrencyLimitExceeded(long retryAfterSeconds) {
        return new QuotaExceededException(
                CONCURRENCY_LIMIT_EXCEEDED,
                "Tenant concurrent request limit exceeded",
                retryAfterSeconds);
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
