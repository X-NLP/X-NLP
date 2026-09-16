package com.xnlp.server.controller;

import com.xnlp.core.model.BenchmarkResult;
import com.xnlp.server.service.BenchmarkService;
import com.xnlp.server.dto.BenchmarkRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/benchmark")
@Validated
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @PostMapping("/{modelName}")
    public BenchmarkResult run(@PathVariable @NotBlank String modelName,
                               @Valid @RequestBody BenchmarkRequest request) {
        return benchmarkService.benchmark(modelName, request.requests(), request.concurrency(), request.text());
    }
}
