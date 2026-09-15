package com.xnlp.server.controller;

import com.xnlp.server.service.NLPTaskService;
import com.xnlp.server.dto.NlpAnalyzeRequest;
import com.xnlp.server.dto.NlpClassificationRequest;
import com.xnlp.server.dto.NlpQaRequest;
import com.xnlp.server.dto.NlpSummarizeRequest;
import com.xnlp.server.dto.NlpTextRequest;
import com.xnlp.server.dto.NlpTranslateRequest;
import com.xnlp.server.service.SemanticSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
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
    public Map<String, Object> analyze(@Valid @RequestBody NlpAnalyzeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.task() != null) body.put("task", request.task());
        if (request.capability() != null) body.put("capability", request.capability());
        body.put("text", request.text());
        if (request.textPair() != null) body.put("textPair", request.textPair());
        if (request.language() != null) body.put("language", request.language());
        if (request.coarse() != null) body.put("coarse", request.coarse());
        if (request.topK() != null) body.put("topK", request.topK());
        if (request.labels() != null) body.put("labels", request.labels());
        if (request.maxLength() != null) body.put("maxLength", request.maxLength());
        if (request.style() != null) body.put("style", request.style());
        if (request.semantic() != null) body.put("semantic", request.semantic());
        return nlpTaskService.analyze(body);
    }

    @PostMapping("/semantic-similarity")
    public Map<String, Object> semanticSimilarity(@Valid @RequestBody SimilarityRequest request) {
        return semanticSearchService.similarity(request.text(), request.textPair());
    }

    @PostMapping("/classify")
    public Map<String, Object> classify(@Valid @RequestBody NlpClassificationRequest request) {
        return nlpTaskService.classify(request.modelName(), request.text(), request.categories());
    }

    @PostMapping("/sentiment")
    public Map<String, Object> sentiment(@Valid @RequestBody NlpTextRequest request) {
        return nlpTaskService.sentiment(request.modelName(), request.text());
    }

    @PostMapping("/summarize")
    public Map<String, Object> summarize(@Valid @RequestBody NlpSummarizeRequest request) {
        return nlpTaskService.summarize(request.modelName(), request.text(), request.maxLength());
    }

    @PostMapping("/ner")
    public Map<String, Object> ner(@Valid @RequestBody NlpTextRequest request) {
        return nlpTaskService.namedEntityRecognition(request.modelName(), request.text());
    }

    @PostMapping("/qa")
    public Map<String, Object> qa(@Valid @RequestBody NlpQaRequest request) {
        return nlpTaskService.questionAnswering(request.modelName(), request.context(), request.question());
    }

    @PostMapping("/translate")
    public Map<String, Object> translate(@Valid @RequestBody NlpTranslateRequest request) {
        return nlpTaskService.translate(request.modelName(), request.text(), request.sourceLanguage());
    }

    public record SimilarityRequest(@NotBlank String text, @NotBlank String textPair) {
    }
}
