package com.xnlp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Operational limits for asynchronous knowledge document ingestion. */
@ConfigurationProperties("xnlp.rag.ingestion")
public class KnowledgeIngestionProperties {

    private String defaultEmbeddingModel = "configured-embedding-model";
    private int batchSize = 32;
    private int maxRetries = 2;
    private Duration retryBackoff = Duration.ofMillis(200);
    private int corePoolSize = 2;
    private int maxPoolSize = 4;
    private int queueCapacity = 100;

    public String getDefaultEmbeddingModel() {
        return defaultEmbeddingModel;
    }

    public void setDefaultEmbeddingModel(String defaultEmbeddingModel) {
        this.defaultEmbeddingModel = requireText(defaultEmbeddingModel, "defaultEmbeddingModel");
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 512) {
            throw new IllegalArgumentException("batchSize must be between 1 and 512");
        }
        this.batchSize = batchSize;
    }

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
        if (retryBackoff == null || retryBackoff.isNegative() || retryBackoff.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("retryBackoff must be between 0 and 1 minute");
        }
        this.retryBackoff = retryBackoff;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("corePoolSize must be positive");
        }
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize < 1) {
            throw new IllegalArgumentException("maxPoolSize must be positive");
        }
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must not be negative");
        }
        this.queueCapacity = queueCapacity;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
