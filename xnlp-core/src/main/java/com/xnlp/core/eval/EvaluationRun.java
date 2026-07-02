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
    private String errorMessage;

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
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
