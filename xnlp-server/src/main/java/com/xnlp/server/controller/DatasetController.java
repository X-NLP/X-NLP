package com.xnlp.server.controller;

import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.server.service.DatasetService;
import com.xnlp.server.service.SemanticSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/datasets")
@Validated
public class DatasetController {

    private final DatasetService datasetService;
    private final SemanticSearchService semanticSearchService;

    public DatasetController(DatasetService datasetService, SemanticSearchService semanticSearchService) {
        this.datasetService = datasetService;
        this.semanticSearchService = semanticSearchService;
    }

    @GetMapping
    public List<EvaluationDataset> list() {
        return datasetService.list();
    }

    @GetMapping("/{id}")
    public EvaluationDataset get(@PathVariable String id) {
        return datasetService.get(id)
                .orElseThrow(() -> new RuntimeException("Dataset not found: " + id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvaluationDataset create(@RequestBody EvaluationDataset dataset) {
        return datasetService.create(dataset);
    }

    @PutMapping("/{id}")
    public EvaluationDataset update(@PathVariable String id, @RequestBody EvaluationDataset dataset) {
        return datasetService.update(id, dataset);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        datasetService.delete(id);
    }

    @GetMapping("/{id}/entries")
    public Map<String, Object> entries(@PathVariable String id,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        List<EvaluationEntry> entries = datasetService.getEntries(id, page, size);
        EvaluationDataset ds = datasetService.get(id).orElseThrow();
        return Map.of("entries", entries, "page", page, "size", size,
                "total", ds.getEntryCount());
    }

    @PostMapping("/{id}/semantic-search")
    public Map<String, Object> semanticSearch(@PathVariable String id,
                                               @Valid @RequestBody SemanticSearchRequest request) {
        return semanticSearchService.searchDataset(id, request.query(), request.topK());
    }

    @GetMapping("/{id}/export")
    public String exportJson(@PathVariable String id) {
        return datasetService.exportJson(id);
    }

    @GetMapping("/count")
    public Map<String, Integer> count() {
        return Map.of("count", datasetService.count());
    }

    public record SemanticSearchRequest(@NotBlank String query,
                                        @Min(1) @Max(100) int topK) {
        public SemanticSearchRequest {
            if (topK == 0) topK = 5;
        }
    }
}
