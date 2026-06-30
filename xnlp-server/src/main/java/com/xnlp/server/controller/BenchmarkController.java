package com.xnlp.server.controller;

import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.server.service.BenchmarkService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/benchmark")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @PostMapping("/{modelName}")
    public BenchmarkResult run(@PathVariable String modelName,
                               @RequestBody Map<String, Object> params) {
        int requests = params.containsKey("requests")
                ? ((Number) params.get("requests")).intValue()
                : 100;
        int concurrency = params.containsKey("concurrency")
                ? ((Number) params.get("concurrency")).intValue()
                : 4;
        String text = (String) params.getOrDefault("text",
                "The future of natural language processing is bright.");
        return benchmarkService.benchmark(modelName, requests, concurrency, text);
    }
}
