package com.xnlp.server.controller;

import com.xnlp.server.service.NLPTaskService;
import com.xnlp.server.service.SemanticSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/nlp")
@Validated
public class NLPTaskController {

    private final NLPTaskService nlpTaskService;
    private final SemanticSearchService semanticSearchService;

    public NLPTaskController(NLPTaskService nlpTaskService, SemanticSearchService semanticSearchService) {
        this.nlpTaskService = nlpTaskService;
        this.semanticSearchService = semanticSearchService;
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> listTasks() {
        return nlpTaskService.listTasks();
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Object> body) {
        return nlpTaskService.analyze(body);
    }

    @PostMapping("/semantic-similarity")
    public Map<String, Object> semanticSimilarity(@Valid @RequestBody SimilarityRequest request) {
        return semanticSearchService.similarity(request.text(), request.textPair());
    }

    @PostMapping("/classify")
    public Map<String, Object> classify(@RequestBody Map<String, Object> body) {
        String modelName = (String) body.get("modelName");
        String text = (String) body.get("text");
        @SuppressWarnings("unchecked")
        List<String> categories = (List<String>) body.get("categories");
        return nlpTaskService.classify(modelName, text, categories);
    }

    @PostMapping("/sentiment")
    public Map<String, Object> sentiment(@RequestBody Map<String, Object> body) {
        return nlpTaskService.sentiment((String) body.get("modelName"), (String) body.get("text"));
    }

    @PostMapping("/summarize")
    public Map<String, Object> summarize(@RequestBody Map<String, Object> body) {
        Integer max = body.get("maxLength") != null
                ? ((Number) body.get("maxLength")).intValue() : null;
        return nlpTaskService.summarize((String) body.get("modelName"), (String) body.get("text"), max);
    }

    @PostMapping("/ner")
    public Map<String, Object> ner(@RequestBody Map<String, Object> body) {
        return nlpTaskService.namedEntityRecognition((String) body.get("modelName"), (String) body.get("text"));
    }

    @PostMapping("/qa")
    public Map<String, Object> qa(@RequestBody Map<String, Object> body) {
        return nlpTaskService.questionAnswering((String) body.get("modelName"),
                (String) body.get("context"), (String) body.get("question"));
    }

    @PostMapping("/translate")
    public Map<String, Object> translate(@RequestBody Map<String, Object> body) {
        return nlpTaskService.translate((String) body.get("modelName"),
                (String) body.get("text"), (String) body.get("sourceLanguage"));
    }

    public record SimilarityRequest(@NotBlank String text, @NotBlank String textPair) {
    }
}
