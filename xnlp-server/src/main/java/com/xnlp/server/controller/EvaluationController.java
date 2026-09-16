package com.xnlp.server.controller;

import com.xnlp.core.eval.CompareResult;
import com.xnlp.core.eval.EvaluationRun;
import com.xnlp.server.service.EvaluationService;
import com.xnlp.server.dto.EvaluationCreateRequest;
import com.xnlp.server.dto.EvaluationRetryRequest;
import com.xnlp.server.dto.EvaluationRetryResponse;
import com.xnlp.server.dto.EvaluationSampleResultResponse;
import com.xnlp.server.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/evaluations")
@Validated
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @GetMapping
    public List<EvaluationRun> list(@RequestParam(required = false) String modelName,
                                    @RequestParam(required = false) String datasetName,
                                    @RequestParam(required = false) String status) {
        return evaluationService.listRuns(modelName, datasetName, status);
    }

    @GetMapping("/{id}")
    public EvaluationRun get(@PathVariable @NotBlank String id) {
        return evaluationService.getRun(id)
                .orElseThrow(() -> new NoSuchElementException("Evaluation run not found: " + id));
    }

    @PostMapping
    public ResponseEntity<EvaluationRun> create(@Valid @RequestBody EvaluationCreateRequest request) {
        EvaluationRun run = evaluationService.startEvaluation(
                request.modelName(), request.datasetId(), request.taskType());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/v1/evaluations/" + run.getId())
                .body(run);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        return evaluationService.streamProgress(id);
    }

    @PostMapping("/{id}/cancel")
    public EvaluationRun cancel(@PathVariable String id) {
        return evaluationService.cancel(id);
    }


    @GetMapping("/{id}/samples")
    public PageResponse<EvaluationSampleResultResponse> samples(
            @PathVariable String id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return evaluationService.sampleResults(id, status, page, size);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<EvaluationRetryResponse> retry(
            @PathVariable String id, @Valid @RequestBody EvaluationRetryRequest request) {
        EvaluationRetryResponse response = evaluationService.retry(id, request.failedOnly());
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/v1/evaluations/" + response.run().getId())
                .body(response);
    }

    @GetMapping("/compare")
    public CompareResult compare(
            @RequestParam @Size(min = 2, max = 20, message = "ids must contain between 2 and 20 values")
            List<@NotBlank String> ids) {
        return evaluationService.compare(ids);
    }
}
