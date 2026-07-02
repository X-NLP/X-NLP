package com.xnlp.server.service;

import com.xnlp.core.eval.*;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates NLP model evaluation against datasets.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private final ModelRegistry registry;
    private final DatasetService datasetService;
    private final MetricsCalculator calculator;
    private final Map<String, EvaluationRun> runs = new ConcurrentHashMap<>();

    public EvaluationService(ModelRegistry registry, DatasetService datasetService,
                             MetricsCalculator calculator) {
        this.registry = registry;
        this.datasetService = datasetService;
        this.calculator = calculator;
    }

    public List<EvaluationRun> listRuns() {
        return runs.values().stream()
                .sorted(Comparator.comparing(EvaluationRun::getCreatedAt).reversed())
                .toList();
    }

    public Optional<EvaluationRun> getRun(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    public EvaluationRun runEvaluation(String modelName, String datasetId, NLPTaskType taskType) {
        EvaluationDataset dataset = datasetService.get(datasetId)
                .orElseThrow(() -> new NoSuchElementException("Dataset not found: " + datasetId));
        NLPTaskType effectiveTask = taskType != null ? taskType : dataset.getTaskType();
        if (effectiveTask == null) {
            throw new IllegalArgumentException("Task type must be specified or present on dataset");
        }
        EvaluationRun run = new EvaluationRun();
        run.setId(UUID.randomUUID().toString());
        run.setModelName(modelName);
        run.setDatasetId(datasetId);
        run.setDatasetName(dataset.getName());
        run.setTaskType(effectiveTask);
        run.setStatus("running");
        run.setCreatedAt(Instant.now());
        runs.put(run.getId(), run);
        long t0 = System.nanoTime();
        try {
            List<Map.Entry<String, String>> predictions = new ArrayList<>();
            for (EvaluationEntry entry : dataset.getEntries()) {
                PredictRequest req = new PredictRequest();
                req.setModelName(modelName);
                req.setText(buildPrompt(effectiveTask, entry));
                PredictResponse resp = registry.predict(req);
                String actual = resp.getText() != null ? resp.getText().strip() : "";
                predictions.add(Map.entry(entry.getExpectedOutput(), actual));
            }
            EvaluationMetrics metrics = calculator.compute(effectiveTask, predictions);
            run.setMetrics(metrics);
            run.setStatus("completed");
        } catch (Exception e) {
            log.error("Evaluation failed: runId={}", run.getId(), e);
            run.setStatus("failed");
            run.setErrorMessage(e.getMessage());
        }
        run.setCompletedAt(Instant.now());
        run.setElapsedSeconds((System.nanoTime() - t0) / 1_000_000_000.0);
        log.info("Evaluation {}: model={} dataset={} task={} status={}",
                run.getId(), modelName, datasetId, effectiveTask, run.getStatus());
        return run;
    }

    public CompareResult compare(List<String> runIds) {
        if (runIds == null || runIds.size() < 2) {
            throw new IllegalArgumentException("At least 2 run IDs required");
        }
        CompareResult result = new CompareResult();
        List<EvaluationRun> selected = new ArrayList<>();
        for (String id : runIds) {
            EvaluationRun r = runs.get(id);
            if (r == null) throw new NoSuchElementException("Run not found: " + id);
            selected.add(r);
        }
        result.setRuns(selected);
        List<String> metricNames = List.of("accuracy", "f1Macro", "precisionMacro", "recallMacro",
                "rouge1", "rouge2", "rougeL", "bleu", "exactMatch", "f1Score", "entityF1");
        for (String mn : metricNames) {
            List<Double> vals = new ArrayList<>();
            for (EvaluationRun r : selected) {
                EvaluationMetrics m = r.getMetrics();
                if (m == null) { vals.add(null); continue; }
                vals.add(getMetricValue(m, mn));
            }
            if (vals.stream().anyMatch(Objects::nonNull)) {
                result.getMetricValues().put(mn, vals);
            }
        }
        // Deltas from first run
        for (var e : result.getMetricValues().entrySet()) {
            List<Double> vals = e.getValue();
            if (vals.get(0) == null) continue;
            List<Double> d = new ArrayList<>();
            d.add(0.0);
            for (int i = 1; i < vals.size(); i++) {
                d.add(vals.get(i) != null ? vals.get(i) - vals.get(0) : null);
            }
            result.getDeltas().put(e.getKey(), d);
        }
        String best = null;
        double bestScore = -1;
        for (EvaluationRun r : selected) {
            if (r.getMetrics() == null) continue;
            if (r.getMetrics().getAccuracy() > bestScore) {
                bestScore = r.getMetrics().getAccuracy();
                best = r.getId();
            }
        }
        result.setBestRunId(best);
        result.setSummary("Comparison of " + selected.size() + " evaluation runs.");
        return result;
    }

    private Double getMetricValue(EvaluationMetrics m, String name) {
        return switch (name) {
            case "accuracy" -> m.getAccuracy() > 0 || m.getTotalEntries() > 0 ? m.getAccuracy() : null;
            case "f1Macro" -> m.getF1Macro();
            case "precisionMacro" -> m.getPrecisionMacro();
            case "recallMacro" -> m.getRecallMacro();
            case "rouge1" -> m.getRouge1();
            case "rouge2" -> m.getRouge2();
            case "rougeL" -> m.getRougeL();
            case "bleu" -> m.getBleu();
            case "exactMatch" -> m.getExactMatch();
            case "f1Score" -> m.getF1Score();
            case "entityF1" -> m.getEntityF1();
            default -> null;
        };
    }

    private String buildPrompt(NLPTaskType task, EvaluationEntry entry) {
        String input = entry.getInput();
        return switch (task) {
            case TEXT_CLASSIFICATION -> "Classify the following text into exactly one category. "
                    + "Reply with only the category name, nothing else.\n\nText: " + input;
            case SENTIMENT_ANALYSIS -> "Analyze the sentiment of this text. "
                    + "Reply with only one word: positive, negative, or neutral.\n\nText: " + input;
            case SUMMARIZATION -> "Summarize the following text in one short sentence. "
                    + "Reply with only the summary.\n\nText: " + input;
            case QUESTION_ANSWERING -> "Answer the question based on the context. "
                    + "Reply with only the answer.\n\n" + input;
            case NAMED_ENTITY_RECOGNITION -> "Extract all named entities from the text. "
                    + "Output one entity per line as TYPE: value\n\nText: " + input;
            case TRANSLATION -> "Translate the following text to English. "
                    + "Reply with only the translation.\n\nText: " + input;
        };
    }
}
