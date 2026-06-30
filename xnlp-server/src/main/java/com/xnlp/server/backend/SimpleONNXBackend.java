package com.xnlp.server.backend;

import com.xnlp.core.engine.InferenceEngine;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleONNXBackend implements InferenceEngine {

    private static final Logger log = LoggerFactory.getLogger(SimpleONNXBackend.class);
    private final Set<String> loadedModels = ConcurrentHashMap.newKeySet();

    @Override
    public String backendName() {
        return "onnx";
    }

    @Override
    public boolean supports(String modelPath) {
        return modelPath != null && (modelPath.endsWith(".onnx")
                || modelPath.endsWith(".ort"));
    }

    @Override
    public void load(String modelName, String modelPath,
                     Map<String, Object> options) {
        log.info("Loading ONNX model {} from {}", modelName, modelPath);
        loadedModels.add(modelName);
    }

    @Override
    public void unload(String modelName) {
        loadedModels.remove(modelName);
    }

    @Override
    public boolean isLoaded(String modelName) {
        return loadedModels.contains(modelName);
    }

    @Override
    public PredictResponse predict(PredictRequest request) {
        return PredictResponse.ok(
                "[onnx:" + request.getModelName() + "] " + request.getText(),
                request.getModelName(), 0.001);
    }

    @Override
    public void close() {
        loadedModels.clear();
    }
}
