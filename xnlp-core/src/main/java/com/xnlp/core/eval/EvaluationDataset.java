package com.xnlp.core.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A named collection of evaluation entries for a specific NLP task.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluationDataset {

    private String id;
    private String name;
    private String description;
    private NLPTaskType taskType;
    private List<EvaluationEntry> entries = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private int entryCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public NLPTaskType getTaskType() { return taskType; }
    public void setTaskType(NLPTaskType taskType) { this.taskType = taskType; }
    public List<EvaluationEntry> getEntries() { return entries; }
    public void setEntries(List<EvaluationEntry> entries) { this.entries = entries != null ? entries : new ArrayList<>(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getEntryCount() { return entryCount; }
    public void setEntryCount(int entryCount) { this.entryCount = entryCount; }
}
