package com.xnlp.server.controller;

import com.xnlp.core.rag.eval.RetrievalEvaluationRun;
import com.xnlp.core.rag.eval.RetrievalEvaluationSampleResult;
import com.xnlp.server.dto.RetrievalEvaluationCreateRequest;
import com.xnlp.server.service.RetrievalEvaluationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP API for persistent asynchronous retrieval evaluations. */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/retrieval-evaluations")
@Validated
@Profile("!memory")
public class RetrievalEvaluationController {

    private final RetrievalEvaluationService service;

    public RetrievalEvaluationController(RetrievalEvaluationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RetrievalEvaluationRun> create(
            @PathVariable @NotBlank String knowledgeBaseId,
            @Valid @RequestBody RetrievalEvaluationCreateRequest request) {
        RetrievalEvaluationRun run = service.start(knowledgeBaseId, request);
        String location = "/api/v1/knowledge-bases/" + knowledgeBaseId
                + "/retrieval-evaluations/" + run.id();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, location)
                .body(run);
    }

    @GetMapping
    public List<RetrievalEvaluationRun> list(
            @PathVariable @NotBlank String knowledgeBaseId) {
        return service.list(knowledgeBaseId);
    }

    @GetMapping("/{runId}")
    public RetrievalEvaluationRun get(
            @PathVariable @NotBlank String knowledgeBaseId,
            @PathVariable @NotBlank String runId) {
        return service.get(knowledgeBaseId, runId);
    }

    @GetMapping("/{runId}/samples")
    public List<RetrievalEvaluationSampleResult> samples(
            @PathVariable @NotBlank String knowledgeBaseId,
            @PathVariable @NotBlank String runId) {
        return service.samples(knowledgeBaseId, runId);
    }
}
