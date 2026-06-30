package com.xnlp.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Server-level configuration for the X-NLP serving runtime.
 */
public class ServerConfig {

    private String host = "0.0.0.0";
    private int port = 8760;
    private int maxWorkers = Runtime.getRuntime().availableProcessors();
    @JsonProperty("request_timeout_seconds")
    private int requestTimeoutSeconds = 60;
    @JsonProperty("max_batch_size")
    private int maxBatchSize = 32;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getMaxWorkers() { return maxWorkers; }
    public void setMaxWorkers(int maxWorkers) { this.maxWorkers = maxWorkers; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
}
