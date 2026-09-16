package com.xnlp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Operational limits for retrieval-augmented chat generation. */
@ConfigurationProperties("xnlp.rag.chat")
public class RagChatProperties {

    private int maxContextCharacters = 12_000;
    private int citationExcerptCharacters = 240;
    private Duration defaultTimeout = Duration.ofSeconds(30);
    private Duration maxTimeout = Duration.ofMinutes(2);
    private int corePoolSize = 2;
    private int maxPoolSize = 8;
    private int queueCapacity = 100;

    public int getMaxContextCharacters() {
        return maxContextCharacters;
    }

    public void setMaxContextCharacters(int maxContextCharacters) {
        if (maxContextCharacters < 256 || maxContextCharacters > 200_000) {
            throw new IllegalArgumentException("maxContextCharacters must be between 256 and 200000");
        }
        this.maxContextCharacters = maxContextCharacters;
    }

    public int getCitationExcerptCharacters() {
        return citationExcerptCharacters;
    }

    public void setCitationExcerptCharacters(int citationExcerptCharacters) {
        if (citationExcerptCharacters < 32 || citationExcerptCharacters > 2_000) {
            throw new IllegalArgumentException("citationExcerptCharacters must be between 32 and 2000");
        }
        this.citationExcerptCharacters = citationExcerptCharacters;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = requireDuration(defaultTimeout, "defaultTimeout");
    }

    public Duration getMaxTimeout() {
        return maxTimeout;
    }

    public void setMaxTimeout(Duration maxTimeout) {
        this.maxTimeout = requireDuration(maxTimeout, "maxTimeout");
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 1 || corePoolSize > 64) {
            throw new IllegalArgumentException("corePoolSize must be between 1 and 64");
        }
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize < 1 || maxPoolSize > 128) {
            throw new IllegalArgumentException("maxPoolSize must be between 1 and 128");
        }
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity < 0 || queueCapacity > 10_000) {
            throw new IllegalArgumentException("queueCapacity must be between 0 and 10000");
        }
        this.queueCapacity = queueCapacity;
    }

    public void validate() {
        if (defaultTimeout.compareTo(maxTimeout) > 0) {
            throw new IllegalArgumentException("defaultTimeout must not exceed maxTimeout");
        }
        if (corePoolSize > maxPoolSize) {
            throw new IllegalArgumentException("corePoolSize must not exceed maxPoolSize");
        }
    }

    private static Duration requireDuration(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(field + " must be between 1 millisecond and 10 minutes");
        }
        return value;
    }
}
