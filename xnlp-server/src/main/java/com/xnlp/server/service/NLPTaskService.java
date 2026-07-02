package com.xnlp.server.service;

import com.xnlp.core.eval.NLPTaskType;
import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import com.xnlp.core.registry.ModelRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in NLP task execution using prompt-based LLM calls.
 *
 * <p>Each task is implemented by constructing a task-specific prompt,
 * calling the model, and extracting a structured answer.
 */
@Service
public class NLPTaskService {

    private static final Logger log = LoggerFactory.getLogger(NLPTaskService.class);

    private final ModelRegistry registry;

    public NLPTaskService(ModelRegistry registry) {
        this.registry = registry;
    }

    public List<Map<String, Object>> listTasks() {
        return List.of(
                taskEntry("TEXT_CLASSIFICATION", "Classify text into predefined categories",
                        Map.of("categories", "array of category names")),
                taskEntry("SENTIMENT_ANALYSIS", "Detect positive/negative/neutral sentiment",
                        Map.of()),
                taskEntry("SUMMARIZATION", "Generate a concise summary of input text",
                        Map.of("max_length", "maximum output length")),
                taskEntry("NAMED_ENTITY_RECOGNITION", "Extract named entities (persons, orgs, locations, etc.)",
                        Map.of()),
                taskEntry("QUESTION_ANSWERING", "Answer a question given a context passage",
                        Map.of("context", "context passage", "question", "the question")),
                taskEntry("TRANSLATION", "Translate text to English",
                        Map.of("source_language", "source language code"))
        );
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

    private Map<String, Object> taskEntry(String name, String description, Map<String, Object> params) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("task", name);
        entry.put("description", description);
        entry.put("parameters", params);
        return entry;
    }
}
