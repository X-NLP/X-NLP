package com.xnlp.server.controller;

import com.xnlp.server.dto.PageResponse;
import com.xnlp.server.dto.pipeline.*;
import com.xnlp.server.pipeline.PipelineDagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Validated
public class PipelineDagController {
    private static final int MAX_PAGE = 10_000_000;
    private final PipelineDagService pipelines;

    public PipelineDagController(PipelineDagService pipelines) { this.pipelines = pipelines; }

    @PostMapping("/api/v1/pipelines")
    public ResponseEntity<PipelineResponse> create(@Valid @RequestBody PipelineCreateRequest request) {
        PipelineResponse response = pipelines.create(request);
        return ResponseEntity.created(java.net.URI.create("/api/v1/pipelines/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/pipelines/{id}")
    public PipelineResponse get(@PathVariable String id) {
        return pipelines.get(id);
    }

    @PutMapping("/api/v1/pipelines/{id}")
    public PipelineResponse update(@PathVariable String id, @Valid @RequestBody PipelineUpdateRequest request) {
        return pipelines.update(id, request);
    }

    @PostMapping("/api/v1/pipelines/{id}/runs")
    public ResponseEntity<PipelineRunResponse> run(@PathVariable String id,
                                                    @Valid @RequestBody PipelineRunCreateRequest request) {
        PipelineRunResponse response = pipelines.start(id, request);
        return ResponseEntity.accepted().location(java.net.URI.create("/api/v1/pipeline-runs/" + response.id())).body(response);
    }

    @GetMapping("/api/v1/pipeline-runs")
    public PageResponse<PipelineRunResponse> runs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String pipelineId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return pipelines.listRuns(status, pipelineId, page, size);
    }

    @GetMapping("/api/v1/pipeline-runs/{runId}")
    public PipelineRunResponse run(@PathVariable String runId) { return pipelines.getRun(runId); }

    @PostMapping("/api/v1/pipeline-runs/{runId}/cancel")
    public PipelineRunResponse cancel(@PathVariable String runId) { return pipelines.cancel(runId); }

    @GetMapping(value = "/api/v1/pipeline-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> events(@PathVariable String runId,
                                    @RequestHeader(name = "Last-Event-ID", defaultValue = "0") @Min(0) long lastEventId) {
        try {
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(pipelines.streamEvents(runId, lastEventId));
        } catch (com.xnlp.server.pipeline.PipelineDagException ex) {
            if (ex.reason() == com.xnlp.server.pipeline.PipelineDagException.Reason.PIPELINE_RUN_NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
            throw ex;
        }
    }

    @GetMapping(value = "/api/v1/pipeline-runs/{runId}/trace", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipelineTraceDownloadResponse> trace(@PathVariable String runId,
                                                                @RequestParam(defaultValue = "json") String format) {
        if (!"json".equalsIgnoreCase(format)) throw new IllegalArgumentException("Only json trace format is supported");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"pipeline-trace-" + runId + ".json\"").body(pipelines.trace(runId));
    }
}
