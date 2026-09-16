package com.xnlp.server.controller;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.model.ModelInfo;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.server.dto.ModelTestRequest;
import com.xnlp.server.dto.ProviderDiagnosticResponse;
import com.xnlp.server.dto.BatchPredictRequest;
import com.xnlp.server.service.InferenceService;
import com.xnlp.server.service.ModelService;
import com.xnlp.server.service.ProviderDiagnosticsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelService modelService;
    private final InferenceService inferenceService;
    private final ProviderDiagnosticsService diagnosticsService;

    public ModelController(ModelService modelService, InferenceService inferenceService,
                           ProviderDiagnosticsService diagnosticsService) {
        this.modelService = modelService;
        this.inferenceService = inferenceService;
        this.diagnosticsService = diagnosticsService;
    }

    @GetMapping
    public List<ModelInfo> list() {
        return modelService.listModels();
    }

    @GetMapping("/runtime")
    public List<ModelInfo> runtime() {
        return modelService.listRuntimeModels();
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return modelService.capabilities();
    }

    @GetMapping("/diagnostics")
    public ProviderDiagnosticResponse diagnostics() {
        return diagnosticsService.diagnose(false);
    }

    @PostMapping("/diagnostics/probe")
    public ProviderDiagnosticResponse probeDiagnostics() {
        return diagnosticsService.diagnose(true);
    }

    @GetMapping("/{name}")
    public ModelInfo get(@PathVariable String name) {
        return modelService.getModel(name);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelInfo save(@Valid @RequestBody ModelConfig config) {
        return modelService.saveModel(config);
    }

    @PostMapping("/{name}/activate")
    public ModelInfo activate(@PathVariable String name) {
        return modelService.activateModel(name);
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        modelService.deleteModel(name);
    }

    @PostMapping("/{name}/unload")
    public void unload(@PathVariable String name) {
        modelService.unloadModel(name);
    }

    @PostMapping("/{name}/predict")
    public PredictResponse predict(@PathVariable String name,
                                   @Valid @RequestBody PredictRequest request) {
        request.setModelName(name);
        return inferenceService.predict(request);
    }

    @PostMapping("/{name}/test")
    public Map<String, Object> test(@PathVariable String name,
                                    @RequestBody ModelTestRequest request) {
        return modelService.testModel(name, request);
    }

    /** Bounded batch inference with item-level success/failure details. */
    @PostMapping("/{name}/batch-predict")
    public Map<String, Object> batchPredict(@PathVariable String name,
                                             @Valid @RequestBody BatchPredictRequest request) {
        return inferenceService.batchPredict(name, request.requests());
    }
}
