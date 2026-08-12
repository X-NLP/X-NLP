package com.xnlp.server.service;

import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.server.config.XNLPProperties;
import com.xnlp.core.registry.ModelRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inference service that delegates to the ModelRegistry.
 *
 * <p>The registry itself calls Spring AI's {@code ChatModel} via the
 * pipeline-managed {@code predict()} method.  This service adds
 * Micrometer observation (tracing spans) and custom metrics recording.
 */
@Service
public class InferenceService {

    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);

    private final ModelRegistry registry;
    private final MetricsService metrics;
    private final XNLPProperties properties;

    public InferenceService(ModelRegistry registry, MetricsService metrics, XNLPProperties properties) {
        this.registry = registry;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Observed(name = "xnlp.predict",
              contextualName = "predict",
              lowCardinalityKeyValues = {"component", "inference"})
    public PredictResponse predict(PredictRequest request) {
        log.debug("Predict: model={} textLen={}",
                request.getModelName(),
                request.getText() != null ? request.getText().length() : 0);
        long t0 = System.nanoTime();
        boolean success = true;
        try {
            return registry.predict(request);
        } catch (RuntimeException e) {
            success = false;
            throw e;
        } finally {
            long elapsed = System.nanoTime() - t0;
            metrics.recordPredict(request.getModelName(), success, elapsed);
        }
    }

    /**
     * Execute a bounded batch while keeping item-level failures visible.
     * The operation is intentionally sequential: provider rate limits and
     * model thread-safety are safer defaults than an unbounded parallel fan-out.
     */
    @Observed(name = "xnlp.predict.batch", contextualName = "batch-predict")
    public Map<String, Object> batchPredict(String modelName, List<PredictRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("requests must not be empty");
        }
        int maxBatchSize = properties.getServer().getMaxBatchSize();
        if (maxBatchSize > 0 && requests.size() > maxBatchSize) {
            throw new IllegalArgumentException("Batch size exceeds configured maximum of " + maxBatchSize);
        }

        List<Map<String, Object>> results = new ArrayList<>(requests.size());
        int successful = 0;
        for (int i = 0; i < requests.size(); i++) {
            PredictRequest request = requests.get(i);
            request.setModelName(modelName);
            try {
                PredictResponse response = predict(request);
                results.add(Map.of("index", i, "success", true, "response", response));
                successful++;
            } catch (RuntimeException e) {
                // Do not hide which item failed; callers can retry only the failed slice.
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("index", i);
                failure.put("success", false);
                failure.put("error", e.getMessage());
                results.add(failure);
            }
        }
        return Map.of(
                "model", modelName,
                "totalRequests", requests.size(),
                "successful", successful,
                "failed", requests.size() - successful,
                "results", results);
    }
}
