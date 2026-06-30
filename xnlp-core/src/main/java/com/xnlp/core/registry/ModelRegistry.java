package com.xnlp.core.registry;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.engine.InferenceEngine;
import com.xnlp.core.errors.BackendNotSupportedError;
import com.xnlp.core.errors.ModelLoadError;
import com.xnlp.core.errors.ModelNotFoundError;
import com.xnlp.core.errors.PredictionError;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.pipeline.PipelineManager;
import com.xnlp.core.pipeline.TextNormalizerPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for model management.
 *
 * <p>Maintains the mapping between model names and their loaded
 * {@link InferenceEngine} instances. Backends are discovered via SPI and
 * the registry picks the first compatible one when loading. Predictions
 * flow through the configured {@link PipelineManager} for pre/post-processing.
 */
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Map<String, ModelInfo> models = new ConcurrentHashMap<>();
    private final Map<String, InferenceEngine> engines = new ConcurrentHashMap<>();
    private final List<InferenceEngine> backends = new CopyOnWriteArrayList<>();
    private final PipelineManager pipelineManager = new PipelineManager();
    private final String activeModel;

    public ModelRegistry() {
        this(null);
    }

    public ModelRegistry(String activeModel) {
        this.activeModel = activeModel;
        this.pipelineManager.register(new TextNormalizerPipeline());
    }

    /** Register a backend engine. Call during initialization. */
    public void registerBackend(InferenceEngine engine) {
        backends.add(engine);
        log.info("Registered backend: {}", engine.backendName());
    }

    /** Register a processing pipeline. */
    public void registerPipeline(com.xnlp.core.pipeline.ProcessingPipeline pipeline) {
        pipelineManager.register(pipeline);
    }

    /** Access the pipeline manager for external configuration. */
    public PipelineManager getPipelineManager() {
        return pipelineManager;
    }

    public List<InferenceEngine> getBackends() {
        return Collections.unmodifiableList(backends);
    }

    /** Load a model from configuration. */
    public ModelInfo loadModel(ModelConfig cfg) {
        InferenceEngine engine = resolveEngine(cfg.getModelPath(), cfg.getBackend());
        if (engine == null) {
            throw new BackendNotSupportedError(
                    "No backend available for model " + cfg.getName(),
                    Map.of("model_path", cfg.getModelPath(), "backend", cfg.getBackend()));
        }

        try {
            engine.load(cfg.getName(), cfg.getModelPath(), cfg.getOptions());
        } catch (Exception e) {
            throw new ModelLoadError("Failed to load model " + cfg.getName(), e);
        }

        engines.put(cfg.getName(), engine);
        ModelInfo info = ModelInfo.fromConfig(cfg);
        info.setStatus("loaded");
        info.setLoadedAt(Instant.now());
        models.put(cfg.getName(), info);
        log.info("Model loaded: {} via {}", cfg.getName(), engine.backendName());
        return info;
    }

    /** Unload a model and release resources. */
    public void unloadModel(String name) {
        InferenceEngine engine = engines.remove(name);
        if (engine != null) {
            engine.unload(name);
        }
        models.remove(name);
    }

    /** Run inference through engine with pre/post-processing pipelines. */
    public PredictResponse predict(PredictRequest request) {
        String modelName = resolveModelName(request.getModelName());
        InferenceEngine engine = engines.get(modelName);
        if (engine == null) {
            throw new ModelNotFoundError("Model not loaded: " + modelName);
        }
        PredictRequest processed = pipelineManager.applyPreProcessing(modelName, request);
        if (processed == null) {
            throw new PredictionError(
                    "Request rejected by pre-processing pipeline for model " + modelName);
        }
        request.setModelName(modelName); processed.setModelName(modelName); PredictResponse response = engine.predict(processed);
        PredictResponse postProcessed = pipelineManager.applyPostProcessing(modelName, response);
        return postProcessed != null ? postProcessed : response;
    }

    /** List all registered models. */
    public List<ModelInfo> listModels() {
        return List.copyOf(models.values());
    }

    /** Get info for a specific model. */
    public ModelInfo getModel(String name) {
        ModelInfo info = models.get(name);
        if (info == null) {
            throw new ModelNotFoundError("Model not found: " + name);
        }
        return info;
    }

    /** Unload all models and close all engines. */
    public void shutdown() {
        new ArrayList<>(models.keySet()).forEach(this::unloadModel);
        for (InferenceEngine engine : backends) {
            try { engine.close(); } catch (Exception ignored) { }
        }
        backends.clear();
    }

    /** Round-trip benchmark helper. */
    public PredictResponse benchmarkPredict(PredictRequest request) {
        return predict(request);
    }

    private String resolveModelName(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (activeModel != null) {
            return activeModel;
        }
        var loaded = new ArrayList<>(models.keySet());
        if (loaded.isEmpty()) {
            throw new ModelNotFoundError("No models are loaded");
        }
        if (loaded.size() > 1) {
            throw new ModelNotFoundError(
                    "Multiple models loaded; specify model name. Available: " + loaded);
        }
        return loaded.get(0);
    }

    private InferenceEngine resolveEngine(String modelPath, String preferredBackend) {
        if (backends.isEmpty()) {
            return null;
        }
        if (!"auto".equals(preferredBackend)) {
            return backends.stream()
                    .filter(e -> e.backendName().equalsIgnoreCase(preferredBackend))
                    .findFirst()
                    .orElse(null);
        }
        return backends.stream()
                .filter(e -> e.supports(modelPath))
                .findFirst()
                .orElse(null);
    }
}
