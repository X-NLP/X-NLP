package com.xnlp.server.service;

import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InferenceService {

    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);

    private final ModelRegistry registry;
    private final MetricsService metrics;

    public InferenceService(ModelRegistry registry, MetricsService metrics) {
        this.registry = registry;
        this.metrics = metrics;
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
}
