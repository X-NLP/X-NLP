package com.xnlp.core.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comparison of two or more evaluation runs side by side.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompareResult {

    private List<EvaluationRun> runs = new ArrayList<>();
    private Map<String, List<Double>> metricValues = new LinkedHashMap<>();
    private Map<String, List<Double>> deltas = new LinkedHashMap<>();
    private String bestRunId;
    private String summary;

    public List<EvaluationRun> getRuns() { return runs; }
    public void setRuns(List<EvaluationRun> runs) { this.runs = runs; }
    public Map<String, List<Double>> getMetricValues() { return metricValues; }
    public void setMetricValues(Map<String, List<Double>> metricValues) { this.metricValues = metricValues; }
    public Map<String, List<Double>> getDeltas() { return deltas; }
    public void setDeltas(Map<String, List<Double>> deltas) { this.deltas = deltas; }
    public String getBestRunId() { return bestRunId; }
    public void setBestRunId(String bestRunId) { this.bestRunId = bestRunId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
