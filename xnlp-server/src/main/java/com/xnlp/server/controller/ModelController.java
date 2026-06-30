package com.xnlp.server.controller;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.server.service.InferenceService;
import com.xnlp.server.service.ModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelService modelService;
    private final InferenceService inferenceService;

    public ModelController(ModelService modelService, InferenceService inferenceService) {
        this.modelService = modelService;
        this.inferenceService = inferenceService;
    }

    @GetMapping
    public List<ModelInfo> list() {
        return modelService.listModels();
    }

    @GetMapping("/{name}")
    public ModelInfo get(@PathVariable String name) {
        return modelService.getModel(name);
    }

    @PostMapping
    public ModelInfo load(@Valid @RequestBody ModelConfig config) {
        return modelService.loadModel(config);
    }

    @DeleteMapping("/{name}")
    public void unload(@PathVariable String name) {
        modelService.unloadModel(name);
    }

    @PostMapping("/{name}/predict")
    public PredictResponse predict(@PathVariable String name,
                                   @Valid @RequestBody PredictRequest request) {
        request.setModelName(name);
        return inferenceService.predict(request);
    }
}
