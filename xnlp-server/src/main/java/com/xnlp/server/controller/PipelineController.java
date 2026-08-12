package com.xnlp.server.controller;

import com.xnlp.server.dto.PipelineExecuteRequest;
import com.xnlp.server.dto.PipelineTraceResponse;
import com.xnlp.server.nlp.CapabilityRegistry;
import com.xnlp.server.service.PipelineTraceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipelines")
public class PipelineController {

    private final PipelineTraceService traceService;
    private final CapabilityRegistry capabilityRegistry;

    public PipelineController(PipelineTraceService traceService, CapabilityRegistry capabilityRegistry) {
        this.traceService = traceService;
        this.capabilityRegistry = capabilityRegistry;
    }

    @GetMapping("/capabilities")
    public List<Map<String, Object>> capabilities() {
        return capabilityRegistry.listAll().stream().map(component -> Map.of(
                "id", component.id(),
                "displayName", component.displayName(),
                "description", component.description(),
                "parameters", component.parameterSchema())).toList();
    }

    @PostMapping("/execute")
    public PipelineTraceResponse execute(@Valid @RequestBody PipelineExecuteRequest request) {
        return traceService.execute(request);
    }
}
