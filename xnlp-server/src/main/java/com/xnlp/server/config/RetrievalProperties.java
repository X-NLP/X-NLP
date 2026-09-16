package com.xnlp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/** Operational policy for knowledge retrieval and optional reranking. */
@ConfigurationProperties("xnlp.rag.retrieval")
public class RetrievalProperties {

    private int maxRetries = 1;
    private Duration retryBackoff = Duration.ofMillis(100);
    private RerankFailurePolicy rerankFailurePolicy = RerankFailurePolicy.FALLBACK;

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 10");
        }
        this.maxRetries = maxRetries;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        if (retryBackoff == null || retryBackoff.isNegative()
                || retryBackoff.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("retryBackoff must be between 0 and 1 minute");
        }
        this.retryBackoff = retryBackoff;
    }

    public RerankFailurePolicy getRerankFailurePolicy() {
        return rerankFailurePolicy;
    }

    public void setRerankFailurePolicy(RerankFailurePolicy rerankFailurePolicy) {
        this.rerankFailurePolicy = Objects.requireNonNull(rerankFailurePolicy,
                "rerankFailurePolicy must not be null");
    }

    public enum RerankFailurePolicy {
        FALLBACK,
        FAIL
    }
}
