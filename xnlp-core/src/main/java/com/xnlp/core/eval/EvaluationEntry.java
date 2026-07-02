package com.xnlp.core.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single entry in an evaluation dataset.
 *
 * <p>{@code input} is the text fed to the model. {@code expectedOutput} is
 * the reference answer. {@code labels} holds task-specific annotations
 * (e.g. classification categories, entity spans).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluationEntry {

    private String id;
    private String input;
    private String expectedOutput;
    private Map<String, Object> labels = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public EvaluationEntry() {}

    public EvaluationEntry(String id, String input, String expectedOutput) {
        this.id = id;
        this.input = input;
        this.expectedOutput = expectedOutput;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
    public Map<String, Object> getLabels() { return labels; }
    public void setLabels(Map<String, Object> labels) { this.labels = new LinkedHashMap<>(labels); }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = new LinkedHashMap<>(metadata); }
}
