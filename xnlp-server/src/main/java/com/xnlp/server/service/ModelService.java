package com.xnlp.server.service;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.registry.ModelRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelService {

    private final ModelRegistry registry;

    public ModelService(ModelRegistry registry) {
        this.registry = registry;
    }

    public ModelInfo loadModel(ModelConfig config) {
        return registry.loadModel(config);
    }

    public void unloadModel(String name) {
        registry.unloadModel(name);
    }

    public List<ModelInfo> listModels() {
        return registry.listModels();
    }

    public ModelInfo getModel(String name) {
        return registry.getModel(name);
    }

    public ModelRegistry getRegistry() {
        return registry;
    }
}
