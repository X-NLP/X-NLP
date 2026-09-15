package com.xnlp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Benchmark run result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BenchmarkResult {

    private String model;
    private int totalRequests;
    private double requestsPerSecond;
    private double latencyAvgMs;
    private double latencyP50Ms;
    private double latencyP95Ms;
    private double latencyP99Ms;
    private int successfulRequests;
    private int failedRequests;
    private double successRate;
    private String failureCode;
    private String failureMessage;
    private List<Double> latenciesMs = new ArrayList<>();

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTotalRequests() { return totalRequests; }
    public void setTotalRequests(int totalRequests) { this.totalRequests = totalRequests; }
    public double getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(double requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
    public double getLatencyAvgMs() { return latencyAvgMs; }
    public void setLatencyAvgMs(double latencyAvgMs) { this.latencyAvgMs = latencyAvgMs; }
    public double getLatencyP50Ms() { return latencyP50Ms; }
    public void setLatencyP50Ms(double latencyP50Ms) { this.latencyP50Ms = latencyP50Ms; }
    public double getLatencyP95Ms() { return latencyP95Ms; }
    public void setLatencyP95Ms(double latencyP95Ms) { this.latencyP95Ms = latencyP95Ms; }
    public double getLatencyP99Ms() { return latencyP99Ms; }
    public void setLatencyP99Ms(double latencyP99Ms) { this.latencyP99Ms = latencyP99Ms; }
    public int getSuccessfulRequests() { return successfulRequests; }
    public void setSuccessfulRequests(int successfulRequests) { this.successfulRequests = successfulRequests; }
    public int getFailedRequests() { return failedRequests; }
    public void setFailedRequests(int failedRequests) { this.failedRequests = failedRequests; }
    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public List<Double> getLatenciesMs() { return Collections.unmodifiableList(latenciesMs); }
    public void setLatenciesMs(List<Double> latenciesMs) { this.latenciesMs = new ArrayList<>(latenciesMs); }
}
