package com.xnlp.server.controller;

import com.xnlp.server.service.ModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ModelService modelService;

    public HealthController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "loaded_models", modelService.listModels().size()
        );
    }
}
