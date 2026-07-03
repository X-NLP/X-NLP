package com.xnlp.server.service;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.dto.ModelTestRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {

    private final ModelRegistry registry;
    private final ModelCatalogService catalog;
    private final ModelConnectionTestService connectionTestService;

    public ModelService(ModelRegistry registry, ModelCatalogService catalog,
                        ModelConnectionTestService connectionTestService) {
        this.registry = registry;
        this.catalog = catalog;
        this.connectionTestService = connectionTestService;
    }

    public ModelInfo saveModel(ModelConfig config) throws IOException {
        return catalog.save(config);
    }

    public ModelInfo activateModel(String name) {
        ModelConfig config = catalog.getConfig(name)
                .orElseThrow(() -> new IllegalArgumentException("Model profile not found: " + name));
        return registry.loadModel(config);
    }

    public void unloadModel(String name) {
        registry.unloadModel(name);
    }

    public void deleteModel(String name) throws IOException {
        registry.unloadModel(name);
        catalog.delete(name);
    }

    public List<ModelInfo> listModels() {
        List<ModelInfo> configured = catalog.list();
        return configured.isEmpty() ? registry.listModels() : configured;
    }

    public List<ModelInfo> listRuntimeModels() {
        return registry.listModels();
    }

    public ModelInfo getModel(String name) {
        return catalog.getConfig(name).map(config -> {
            ModelInfo info = catalog.get(name);
            boolean loaded = registry.listModels().stream().anyMatch(m -> m.getName().equals(name));
            if (loaded) info.setStatus("loaded");
            return info;
        }).orElseGet(() -> registry.getModel(name));
    }

    public Map<String, Object> capabilities() {
        return catalog.capabilities();
    }

    public Map<String, Object> testModel(String name, ModelTestRequest request) {
        ModelConfig config = catalog.getConfig(name)
                .orElseThrow(() -> new IllegalArgumentException("Model profile not found: " + name));
        if (config.getType() == ModelType.CHAT && isRuntimeLoaded(name)
                && config.getProtocol() == ModelProtocol.SPRING_AI_CHAT) {
            PredictRequest predictRequest = new PredictRequest();
            predictRequest.setModelName(name);
            predictRequest.setText(request.getInput());
            PredictResponse response = registry.predict(predictRequest);
            return Map.of(
                    "type", config.getType(),
                    "protocol", config.getProtocol(),
                    "model", response.getModel(),
                    "output", response.getText(),
                    "elapsedSeconds", response.getElapsedSeconds()
            );
        }
        return connectionTestService.test(config, request);
    }

    private boolean isRuntimeLoaded(String name) {
        return registry.listModels().stream().anyMatch(model -> model.getName().equals(name));
    }

    public ModelRegistry getRegistry() {
        return registry;
    }
}
