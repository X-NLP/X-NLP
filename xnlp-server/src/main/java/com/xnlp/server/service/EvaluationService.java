package com.xnlp.server.service;

import com.xnlp.core.eval.*;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.repository.EvaluationRunRepository;
import com.xnlp.core.registry.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Orchestrates NLP model evaluation against datasets.
 *
 * <p>Evaluation is submitted to a bounded executor so HTTP requests return
 * quickly. Progress and terminal state are persisted after each entry, which
 * makes the same API useful for a web UI, CLI and operational monitoring.</p>
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed", "cancelled");

    private final ModelRegistry registry;
    private final DatasetService datasetService;
    private final EvaluationRunRepository runRepository;
    private final MetricsCalculator calculator;
    private final TaskExecutor evaluationTaskExecutor;

    public EvaluationService(ModelRegistry registry, DatasetService datasetService,
                             EvaluationRunRepository runRepository,
                             MetricsCalculator calculator,
                             TaskExecutor evaluationTaskExecutor) {
        this.registry = registry;
        this.datasetService = datasetService;
        this.runRepository = runRepository;
        this.calculator = calculator;
        this.evaluationTaskExecutor = evaluationTaskExecutor;
    }

    public List<EvaluationRun> listRuns() {
        return listRuns(null, null, null);
    }

    public List<EvaluationRun> listRuns(String modelName, String datasetName, String status) {
        return runRepository.findAll().stream()
                .filter(run -> matches(run.getModelName(), modelName))
                .filter(run -> matches(run.getDatasetName(), datasetName))
                .filter(run -> matches(run.getStatus(), status))
                .toList();
    }

    public Optional<EvaluationRun> getRun(String id) {
        return runRepository.findById(id);
    }

    /** Creates and queues a run, returning before model inference begins. */
    public EvaluationRun startEvaluation(String modelName, String datasetId, NLPTaskType taskType) {
        EvaluationRequest request = prepareRequest(modelName, datasetId, taskType);
        EvaluationRun run = request.run();
        try {
            evaluationTaskExecutor.execute(() -> executeEvaluation(run.getId(), request.modelName(),
                    request.datasetId(), request.taskType()));
        } catch (RuntimeException rejected) {
            run.setStatus("failed");
            run.setErrorMessage("Evaluation queue is unavailable: " + rejected.getMessage());
            run.setCompletedAt(Instant.now());
            run.setElapsedSeconds(0);
            runRepository.save(run);
            throw rejected;
        }
        return run;
    }

    /**
     * Synchronous compatibility entry point for SDK/CLI callers that need a
     * blocking operation. New HTTP callers should use {@link #startEvaluation}.
     */
    public EvaluationRun runEvaluation(String modelName, String datasetId, NLPTaskType taskType) {
        EvaluationRequest request = prepareRequest(modelName, datasetId, taskType);
        executeEvaluation(request.run().getId(), request.modelName(), request.datasetId(), request.taskType());
        return getRun(request.run().getId()).orElse(request.run());
    }

    /** Requests cancellation; the worker observes it between dataset entries. */
    public EvaluationRun cancel(String id) {
        EvaluationRun run = getRun(id)
                .orElseThrow(() -> new NoSuchElementException("Evaluation run not found: " + id));
        if (!TERMINAL_STATUSES.contains(run.getStatus())) {
            run.setCancelRequested(true);
            run.setStatus("cancelling");
            runRepository.save(run);
        }
        return run;
    }

    private EvaluationRequest prepareRequest(String modelName, String datasetId, NLPTaskType taskType) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId must not be blank");
        }
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
        run.setStatus("queued");
        run.setCreatedAt(Instant.now());
        run.setTotalEntries(dataset.getEntries() == null ? 0 : dataset.getEntries().size());
        run.setProcessedEntries(0);
        run.setProgressPercent(run.getTotalEntries() == 0 ? 100 : 0);
        run.setCancelRequested(false);
        runRepository.save(run);
        return new EvaluationRequest(run, modelName, datasetId, effectiveTask);
    }

    private void executeEvaluation(String runId, String modelName, String datasetId, NLPTaskType taskType) {
        EvaluationRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Evaluation run disappeared before execution: runId={}", runId);
            return;
        }
        long t0 = System.nanoTime();
        try {
            EvaluationDataset dataset = datasetService.get(datasetId)
                    .orElseThrow(() -> new NoSuchElementException("Dataset not found: " + datasetId));
            List<EvaluationEntry> entries = dataset.getEntries() == null ? List.of() : List.copyOf(dataset.getEntries());
            run.setTotalEntries(entries.size());
            if (isCancellationRequested(runId)) {
                finishCancelled(run, t0);
                return;
            }
            run.setStatus("running");
            runRepository.save(run);

            List<Map.Entry<String, String>> predictions = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                if (isCancellationRequested(runId)) {
                    finishCancelled(run, t0);
                    return;
                }
                EvaluationEntry entry = entries.get(i);
                PredictRequest req = new PredictRequest();
                req.setModelName(modelName);
                req.setText(buildPrompt(taskType, entry));
                PredictResponse resp = registry.predict(req);
                String actual = resp.getText() != null ? resp.getText().strip() : "";
                predictions.add(Map.entry(Objects.toString(entry.getExpectedOutput(), ""), actual));
                run.setProcessedEntries(i + 1);
                run.setProgressPercent(entries.isEmpty() ? 100 : ((i + 1) * 100.0) / entries.size());
                persistProgress(run, runId);
            }

            EvaluationMetrics metrics = calculator.compute(taskType, predictions);
            run.setMetrics(metrics);
            run.setStatus("completed");
            run.setProcessedEntries(entries.size());
            run.setProgressPercent(100);
        } catch (Exception e) {
            log.error("Evaluation failed: runId={}", runId, e);
            run.setStatus("failed");
            run.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        run.setCompletedAt(Instant.now());
        run.setElapsedSeconds((System.nanoTime() - t0) / 1_000_000_000.0);
        persistProgress(run, runId);
        log.info("Evaluation {}: model={} dataset={} task={} status={} progress={}/{}",
                runId, modelName, datasetId, taskType, run.getStatus(),
                run.getProcessedEntries(), run.getTotalEntries());
    }

    private void finishCancelled(EvaluationRun run, long t0) {
        run.setStatus("cancelled");
        run.setCompletedAt(Instant.now());
        run.setElapsedSeconds((System.nanoTime() - t0) / 1_000_000_000.0);
        runRepository.save(run);
        log.info("Evaluation cancelled: runId={} progress={}/{}",
                run.getId(), run.getProcessedEntries(), run.getTotalEntries());
    }

    private boolean isCancellationRequested(String runId) {
        return runRepository.findById(runId).map(EvaluationRun::isCancelRequested).orElse(true);
    }

    private void persistProgress(EvaluationRun run, String runId) {
        // Preserve a concurrent cancel request made by the API thread.
        runRepository.findById(runId).ifPresent(latest -> {
            if (latest.isCancelRequested()) run.setCancelRequested(true);
        });
        runRepository.save(run);
    }

    private static boolean matches(String actual, String expected) {
        return expected == null || expected.isBlank()
                || (actual != null && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)));
    }

    public CompareResult compare(List<String> runIds) {
        if (runIds == null || runIds.size() < 2) {
            throw new IllegalArgumentException("At least 2 run IDs required");
        }
        CompareResult result = new CompareResult();
        List<EvaluationRun> selected = new ArrayList<>();
        for (String id : runIds) {
            EvaluationRun r = runRepository.findById(id)
                    .orElse(null);
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

    private record EvaluationRequest(EvaluationRun run, String modelName, String datasetId, NLPTaskType taskType) {
    }
}
