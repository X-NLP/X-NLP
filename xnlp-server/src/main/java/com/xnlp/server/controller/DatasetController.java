package com.xnlp.server.controller;

import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.server.service.DatasetService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
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
    public EvaluationDataset create(@RequestBody EvaluationDataset dataset) throws IOException {
        return datasetService.create(dataset);
    }

    @PutMapping("/{id}")
    public EvaluationDataset update(@PathVariable String id, @RequestBody EvaluationDataset dataset) throws IOException {
        return datasetService.update(id, dataset);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) throws IOException {
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

    @GetMapping("/{id}/export")
    public String exportJson(@PathVariable String id) {
        return datasetService.exportJson(id);
    }

    @GetMapping("/count")
    public Map<String, Integer> count() {
        return Map.of("count", datasetService.count());
    }
}
