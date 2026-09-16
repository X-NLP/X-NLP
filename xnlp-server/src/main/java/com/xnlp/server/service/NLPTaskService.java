package com.xnlp.server.service;

import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.core.eval.NLPTaskType;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.nlp.CapabilityRegistry;
import com.xnlp.server.nlp.NlpComponentExecution;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in NLP capability metadata and task processing facade.
 *
 * <p>The list endpoint describes the target HanLP-style capability surface.
 * The analyze endpoint delegates to the {@link CapabilityRegistry} which
 * dispatches to individual {@code NlpComponent} implementations.
 * Legacy prompt-based task endpoints are kept for backward compatibility.
 */
@Service
public class NLPTaskService {

    private static final Logger log = LoggerFactory.getLogger(NLPTaskService.class);

    private final ModelRegistry registry;
    private final CapabilityRegistry capabilityRegistry;
    private final SemanticSearchService semanticSearchService;

    public NLPTaskService(ModelRegistry registry, CapabilityRegistry capabilityRegistry,
                          SemanticSearchService semanticSearchService) {
        this.registry = registry;
        this.capabilityRegistry = capabilityRegistry;
        this.semanticSearchService = semanticSearchService;
    }

    /** Delegates to the component-based capability registry. */
    public List<Map<String, Object>> listTasks() {
        return capabilityRegistry.listTasks();
    }

    @Observed(name = "xnlp.task.analyze")
    public Map<String, Object> analyze(Map<String, Object> body) {
        // Accept both the canonical `task` field and the UI/API-friendly
        // `capability` alias so callers can use either vocabulary.
        String requestedTask = body.get("task") != null
                ? body.get("task").toString()
                : body.get("capability") != null ? body.get("capability").toString() : "TOK";
        String taskId = requestedTask.toUpperCase();
        String text = body.get("text") != null
                ? body.get("text").toString().strip() : "";
        String textPair = body.get("textPair") != null
                ? body.get("textPair").toString().strip() : "";
        String language = body.get("language") != null
                ? body.get("language").toString() : "zh";

        // Build NlpContext and dispatch to component via registry
        NlpContext ctx = NlpContext.builder()
                .text(text)
                .textPair(textPair)
                .language(language)
                .param("coarse", body.getOrDefault("coarse", true))
                .param("topK", body.getOrDefault("topK", 5))
                .param("labels", body.get("labels"))
                .param("maxLength", body.getOrDefault("maxLength", 80))
                .param("style", body.getOrDefault("style", "formal"))
                .param("semantic", body.getOrDefault("semantic", false))
                .build();

        // Support legacy task name aliases
        String resolvedId = switch (taskId) {
            case "TOKENIZATION" -> "TOK";
            case "PART_OF_SPEECH" -> "POS";
            case "NAMED_ENTITY_RECOGNITION" -> "NER";
            case "DEPENDENCY_PARSING" -> "DEP";
            case "SEMANTIC_ROLE_LABELING" -> "SRL";
            case "SUMMARIZATION" -> "ABSUM";
            case "TEXT_CLASSIFICATION" -> "CLASSIFICATION";
            case "SENTIMENT_ANALYSIS" -> "SENTIMENT";
            case "TEXT_SIMILARITY" -> "STS";
            default -> taskId;
        };

        if ("STS".equals(resolvedId) && semanticSearchService.isAvailable() && !textPair.isBlank()) {
            Map<String, Object> response = new LinkedHashMap<>(semanticSearchService.similarity(text, textPair));
            response.put("task", taskId);
            response.put("language", language);
            response.put("input", text);
            response.put("textPair", textPair);
            return response;
        }

        NlpComponentExecution execution = capabilityRegistry.executeWithRuntime(resolvedId, ctx);
        ComponentResult cr = execution.result();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("task", taskId);
        response.put("language", language);
        response.put("input", text);
        if (!textPair.isBlank()) response.put("textPair", textPair);
        response.put("result", cr.getData());
        Map<String, Object> runtime = new LinkedHashMap<>(execution.runtime());
        runtime.put("springAiEmbeddingAvailable", semanticSearchService.isAvailable());
        response.put("runtime", runtime);
        return response;
    }

    @Observed(name = "xnlp.task.classify")
    public Map<String, Object> classify(String modelName, String text, List<String> categories) {
        String cats = String.join(", ", categories);
        String prompt = "Classify the following text into exactly one of these categories: "
                + cats + ". Reply with only the category name.\n\nText: " + text;
        PredictResponse resp = predict(modelName, prompt);
        return Map.of("label", resp.getText().strip(), "model", resp.getModel(),
                "elapsed_seconds", resp.getElapsedSeconds());
    }

    @Observed(name = "xnlp.task.sentiment")
    public Map<String, Object> sentiment(String modelName, String text) {
        String prompt = "Analyze the sentiment. Reply with only one word: positive, negative, or neutral.\n\nText: " + text;
        PredictResponse resp = predict(modelName, prompt);
        String label = resp.getText().strip().toLowerCase();
        if (!List.of("positive", "negative", "neutral").contains(label)) {
            label = "neutral";
        }
        return Map.of("label", label, "model", resp.getModel(),
                "elapsed_seconds", resp.getElapsedSeconds());
    }

    @Observed(name = "xnlp.task.summarize")
    public Map<String, Object> summarize(String modelName, String text, Integer maxLength) {
        String limit = maxLength != null ? " in at most " + maxLength + " words" : " in one sentence";
        String prompt = "Summarize the following text" + limit + ". Reply with only the summary.\n\nText: " + text;
        PredictResponse resp = predict(modelName, prompt);
        return Map.of("summary", resp.getText().strip(), "model", resp.getModel(),
                "elapsed_seconds", resp.getElapsedSeconds());
    }

    @Observed(name = "xnlp.task.ner")
    public Map<String, Object> namedEntityRecognition(String modelName, String text) {
        String prompt = "Extract named entities (PERSON, ORGANIZATION, LOCATION, DATE, MISC). "
                + "Output one per line as TYPE: value.\n\nText: " + text;
        PredictResponse resp = predict(modelName, prompt);
        return Map.of("entities", resp.getText().strip(), "model", resp.getModel(),
                "elapsed_seconds", resp.getElapsedSeconds());
    }

    @Observed(name = "xnlp.task.qa")
    public Map<String, Object> questionAnswering(String modelName, String context, String question) {
        String prompt = "Context: " + context + "\n\nQuestion: " + question
                + "\n\nAnswer the question using only the context. Reply with only the answer.";
        PredictResponse resp = predict(modelName, prompt);
        return Map.of("answer", resp.getText().strip(), "model", resp.getModel(),
                "elapsed_seconds", resp.getElapsedSeconds());
    }

    @Observed(name = "xnlp.task.translate")
    public Map<String, Object> translate(String modelName, String text, String sourceLanguage) {
        String src = sourceLanguage != null ? " from " + sourceLanguage : "";
        String prompt = "Translate the following text" + src + " to English. "
                + "Reply with only the translation.\n\nText: " + text;
        PredictResponse resp = predict(modelName, prompt);
        return Map.of("translation", resp.getText().strip(), "model", resp.getModel(),
                "elapsed_seconds", resp.getElapsedSeconds());
    }

    private PredictResponse predict(String modelName, String prompt) {
        PredictRequest req = new PredictRequest();
        req.setModelName(modelName);
        req.setText(prompt);
        return registry.predict(req);
    }

}
