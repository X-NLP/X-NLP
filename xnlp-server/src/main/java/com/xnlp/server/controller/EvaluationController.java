package com.xnlp.server.controller;

import com.xnlp.core.eval.CompareResult;
import com.xnlp.core.eval.EvaluationRun;
import com.xnlp.core.eval.NLPTaskType;
import com.xnlp.server.service.EvaluationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping
    public List<EvaluationRun> list() {
        return evaluationService.listRuns();
    }

    @GetMapping("/{id}")
    public EvaluationRun get(@PathVariable String id) {
        return evaluationService.getRun(id)
                .orElseThrow(() -> new RuntimeException("Evaluation run not found: " + id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationRun create(@RequestBody Map<String, Object> body) {
        String modelName = (String) body.get("modelName");
        String datasetId = (String) body.get("datasetId");
        String taskTypeStr = (String) body.get("taskType");
        NLPTaskType taskType = taskTypeStr != null ? NLPTaskType.valueOf(taskTypeStr) : null;
        return evaluationService.runEvaluation(modelName, datasetId, taskType);
    }

    @GetMapping("/compare")
    public CompareResult compare(@RequestParam List<String> ids) {
        return evaluationService.compare(ids);
    }
}
