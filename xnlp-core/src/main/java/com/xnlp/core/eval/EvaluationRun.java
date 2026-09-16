package com.xnlp.core.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Result of a single evaluation run: a model evaluated against a dataset.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluationRun {

    private String id;
    private String modelName;
    private String datasetId;
    private String datasetName;
    private NLPTaskType taskType;
    private String status;
    private EvaluationMetrics metrics;
    private Instant createdAt;
    private Instant completedAt;
    private double elapsedSeconds;
    private int totalEntries;
    private int processedEntries;
    private double progressPercent;
    private boolean cancelRequested;
    private String errorMessage;
    private Long datasetVersion;
    private String parentRunId;
    private String rootRunId;
    private int attempt;
    private boolean retryFailedOnly;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
    public NLPTaskType getTaskType() { return taskType; }
    public void setTaskType(NLPTaskType taskType) { this.taskType = taskType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public EvaluationMetrics getMetrics() { return metrics; }
    public void setMetrics(EvaluationMetrics metrics) { this.metrics = metrics; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public double getElapsedSeconds() { return elapsedSeconds; }
    public void setElapsedSeconds(double elapsedSeconds) { this.elapsedSeconds = elapsedSeconds; }
    public int getTotalEntries() { return totalEntries; }
    public void setTotalEntries(int totalEntries) { this.totalEntries = Math.max(0, totalEntries); }
    public int getProcessedEntries() { return processedEntries; }
    public void setProcessedEntries(int processedEntries) { this.processedEntries = Math.max(0, processedEntries); }
    public double getProgressPercent() { return progressPercent; }
    public void setProgressPercent(double progressPercent) {
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
    }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(Long datasetVersion) {
        if (datasetVersion != null && datasetVersion < 0) throw new IllegalArgumentException("datasetVersion must not be negative");
        this.datasetVersion = datasetVersion;
    }
    public String getParentRunId() { return parentRunId; }
    public void setParentRunId(String parentRunId) { this.parentRunId = parentRunId; }
    public String getRootRunId() { return rootRunId; }
    public void setRootRunId(String rootRunId) { this.rootRunId = rootRunId; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = Math.max(0, attempt); }
    public boolean isRetryFailedOnly() { return retryFailedOnly; }
    public void setRetryFailedOnly(boolean retryFailedOnly) { this.retryFailedOnly = retryFailedOnly; }
}
