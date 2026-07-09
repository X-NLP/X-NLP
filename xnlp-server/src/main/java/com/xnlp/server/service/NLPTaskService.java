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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in NLP capability metadata and legacy prompt-based task endpoints.
 *
 * <p>The list endpoint describes the target HanLP-style capability surface.
 * Existing POST endpoints are kept for compatibility until pipeline runtimes
 * are wired for dedicated NLP components.
 */
@Service
public class NLPTaskService {

    private static final Logger log = LoggerFactory.getLogger(NLPTaskService.class);

    private final ModelRegistry registry;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[A-Za-z]+(?:[-'][A-Za-z]+)*|\\d+(?:\\.\\d+)?|[^\\s]");
    private static final Set<String> POSITIVE_WORDS = Set.of("好", "优秀", "喜欢", "满意", "高兴", "成功", "提升", "positive", "good", "great", "excellent", "love");
    private static final Set<String> NEGATIVE_WORDS = Set.of("差", "糟糕", "讨厌", "失败", "问题", "风险", "不好", "negative", "bad", "poor", "fail", "risk");

    public NLPTaskService(ModelRegistry registry) {
        this.registry = registry;
    }

    public List<Map<String, Object>> listTasks() {
        return List.of(
                taskEntry("TOK", "Word segmentation with coarse and fine modes",
                        Map.of("language", "zh, en, ja, mul", "coarse", "boolean")),
                taskEntry("POS", "Part-of-speech tagging over segmented tokens",
                        Map.of("language", "zh, en, ja, mul")),
                taskEntry("NER", "Named entity recognition for people, organizations, locations, dates, and numbers",
                        Map.of("language", "zh, en, ja, mul")),
                taskEntry("DEP", "Dependency parsing arcs between tokens",
                        Map.of("language", "zh, en, ja, mul")),
                taskEntry("SDP", "Semantic dependency parsing graph",
                        Map.of("language", "zh, en, ja, mul")),
                taskEntry("SRL", "Semantic role labeling for predicates and arguments",
                        Map.of("language", "zh, en, ja, mul")),
                taskEntry("CON", "Constituency parsing tree",
                        Map.of("language", "zh, en")),
                taskEntry("AMR", "Abstract meaning representation graph",
                        Map.of("language", "en, zh")),
                taskEntry("KEYPHRASE", "Extract key phrases from text",
                        Map.of("topK", "number")),
                taskEntry("EXSUM", "Extractive summarization by selecting source sentences",
                        Map.of("topK", "number")),
                taskEntry("ABSUM", "Abstractive summarization placeholder for model-backed summarizers",
                        Map.of("maxLength", "number")),
                taskEntry("COR", "Text correction with normalized output and edit list",
                        Map.of("language", "zh, en")),
                taskEntry("CLASSIFICATION", "Text classification using configured labels",
                        Map.of("labels", "array of category names")),
                taskEntry("SENTIMENT", "Sentiment analysis",
                        Map.of("labels", "positive, negative, neutral")),
                taskEntry("STS", "Semantic textual similarity",
                        Map.of("text", "string", "textPair", "string")),
                taskEntry("TST", "Text style transfer normalization demo",
                        Map.of("style", "formal or concise")),
                taskEntry("TOKENIZATION", "Segment text into coarse or fine-grained tokens",
                        Map.of("granularity", "coarse or fine")),
                taskEntry("PART_OF_SPEECH", "Assign part-of-speech tags to tokens",
                        Map.of("tokenizer", "upstream tokenizer profile")),
                taskEntry("NAMED_ENTITY_RECOGNITION", "Extract entities such as persons, organizations, locations, and dates",
                        Map.of("schema", "entity label set")),
                taskEntry("DEPENDENCY_PARSING", "Analyze syntactic dependency arcs between tokens",
                        Map.of("tokenizer", "upstream tokenizer profile")),
                taskEntry("SEMANTIC_ROLE_LABELING", "Identify predicates and argument roles in a sentence",
                        Map.of("parser", "upstream parser profile")),
                taskEntry("TEXT_CLASSIFICATION", "Classify text using configured categories or labels",
                        Map.of("labels", "array of category names")),
                taskEntry("TEXT_SIMILARITY", "Compare text similarity using embedding models",
                        Map.of("embeddingModel", "embedding model profile")),
                taskEntry("RERANKING", "Rerank candidate documents for a query",
                        Map.of("rerankingModel", "reranking model profile"))
        );
    }

    @Observed(name = "xnlp.task.analyze")
    public Map<String, Object> analyze(Map<String, Object> body) {
        String task = stringValue(body.get("task"), "TOK").toUpperCase();
        String text = stringValue(body.get("text"), "").strip();
        String textPair = stringValue(body.get("textPair"), "").strip();
        String language = stringValue(body.get("language"), "zh");
        int topK = intValue(body.get("topK"), 5);
        List<String> labels = stringList(body.get("labels"));

        Map<String, Object> result = switch (task) {
            case "TOK", "TOKENIZATION" -> tokenization(text, boolValue(body.get("coarse")));
            case "POS", "PART_OF_SPEECH" -> pos(text);
            case "NER", "NAMED_ENTITY_RECOGNITION" -> nerDemo(text);
            case "DEP", "DEPENDENCY_PARSING" -> dependency(text, false);
            case "SDP" -> dependency(text, true);
            case "SRL", "SEMANTIC_ROLE_LABELING" -> srl(text);
            case "CON" -> constituency(text);
            case "AMR" -> amr(text);
            case "KEYPHRASE" -> keyphrase(text, topK);
            case "EXSUM" -> extractiveSummary(text, topK);
            case "ABSUM", "SUMMARIZATION" -> abstractiveSummary(text, intValue(body.get("maxLength"), 80));
            case "COR" -> correction(text);
            case "CLASSIFICATION", "TEXT_CLASSIFICATION" -> classification(text, labels);
            case "SENTIMENT", "SENTIMENT_ANALYSIS" -> sentimentDemo(text);
            case "STS", "TEXT_SIMILARITY" -> similarity(text, textPair);
            case "TST" -> styleTransfer(text, stringValue(body.get("style"), "formal"));
            default -> Map.of("message", "Unsupported NLP task: " + task);
        };

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("task", task);
        response.put("language", language);
        response.put("input", text);
        if (!textPair.isBlank()) response.put("textPair", textPair);
        response.put("result", result);
        response.put("runtime", Map.of("mode", "builtin-demo", "standard", "hanlp-demo-compatible"));
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

    private Map<String, Object> taskEntry(String name, String description, Map<String, Object> params) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("task", name);
        entry.put("description", description);
        entry.put("parameters", params);
        return entry;
    }

    private Map<String, Object> tokenization(String text, boolean coarse) {
        List<String> tokens = tokens(text, coarse);
        return Map.of("tokens", tokens, "count", tokens.size(), "coarse", coarse);
    }

    private Map<String, Object> pos(String text) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String token : tokens(text, false)) {
            rows.add(Map.of("token", token, "pos", guessPos(token)));
        }
        return Map.of("tokens", rows);
    }

    private Map<String, Object> nerDemo(String text) {
        List<Map<String, Object>> entities = new ArrayList<>();
        addEntityMatches(text, entities, "PERSON", List.of("先生", "女士", "教授", "记者"));
        addEntityMatches(text, entities, "LOCATION", List.of("北京", "上海", "深圳", "广州", "中国", "美国", "日本", "伊拉克", "剑桥"));
        addEntityMatches(text, entities, "ORGANIZATION", List.of("公司", "大学", "委员会", "HanLP", "X-NLP", "联合国"));
        Matcher number = Pattern.compile("\\d+(?:年|月|日|%|\\.\\d+)?").matcher(text);
        while (number.find()) entities.add(entity("NUMBER", number.group(), number.start(), number.end()));
        entities.sort(Comparator.comparingInt(item -> ((Number) item.get("start")).intValue()));
        return Map.of("entities", entities);
    }

    private Map<String, Object> dependency(String text, boolean semantic) {
        List<String> tokens = tokens(text, true);
        List<Map<String, Object>> arcs = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            int head = i == 0 ? -1 : i - 1;
            arcs.add(Map.of("id", i, "token", tokens.get(i), "head", head,
                    "relation", i == 0 ? "root" : semantic ? "semantic-mod" : "dep"));
        }
        return Map.of("nodes", tokens, "arcs", arcs, "type", semantic ? "semantic" : "syntactic");
    }

    private Map<String, Object> srl(String text) {
        List<String> tokens = tokens(text, true);
        String predicate = tokens.stream().filter(token -> guessPos(token).equals("VERB")).findFirst().orElse(tokens.isEmpty() ? "" : tokens.get(0));
        List<Map<String, Object>> roles = new ArrayList<>();
        if (!tokens.isEmpty()) roles.add(Map.of("role", "ARG0", "text", tokens.get(0)));
        if (tokens.size() > 1) roles.add(Map.of("role", "PRED", "text", predicate));
        if (tokens.size() > 2) roles.add(Map.of("role", "ARG1", "text", String.join("", tokens.subList(2, tokens.size()))));
        return Map.of("frames", List.of(Map.of("predicate", predicate, "roles", roles)));
    }

    private Map<String, Object> constituency(String text) {
        List<String> tokens = tokens(text, true);
        return Map.of("tree", Map.of("label", "S", "children", List.of(
                Map.of("label", "NP", "text", tokens.isEmpty() ? "" : tokens.get(0)),
                Map.of("label", "VP", "text", tokens.size() > 1 ? String.join("", tokens.subList(1, tokens.size())) : "")
        )));
    }

    private Map<String, Object> amr(String text) {
        List<String> tokens = tokens(text, true);
        List<Map<String, Object>> triples = new ArrayList<>();
        for (int i = 0; i < Math.min(tokens.size(), 8); i++) {
            triples.add(Map.of("source", "x" + i, "relation", i == 0 ? "instance" : "ARG", "target", tokens.get(i)));
        }
        return Map.of("triples", triples);
    }

    private Map<String, Object> keyphrase(String text, int topK) {
        List<String> phrases = tokens(text, true).stream()
                .filter(token -> token.length() > 1)
                .distinct()
                .limit(Math.max(1, topK))
                .toList();
        return Map.of("keyphrases", phrases);
    }

    private Map<String, Object> extractiveSummary(String text, int topK) {
        List<String> sentences = sentences(text);
        List<String> selected = sentences.stream().limit(Math.max(1, Math.min(topK, 3))).toList();
        return Map.of("sentences", selected, "summary", String.join("", selected));
    }

    private Map<String, Object> abstractiveSummary(String text, int maxLength) {
        String normalized = text.replaceAll("\\s+", " ").strip();
        String summary = normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(1, maxLength)) + "...";
        return Map.of("summary", summary, "maxLength", maxLength);
    }

    private Map<String, Object> correction(String text) {
        String corrected = text.replace("的的", "的").replace("地地", "地").replaceAll("\\s+", " ").strip();
        List<Map<String, Object>> edits = new ArrayList<>();
        if (!corrected.equals(text)) edits.add(Map.of("from", text, "to", corrected, "type", "normalization"));
        return Map.of("corrected", corrected, "edits", edits);
    }

    private Map<String, Object> classification(String text, List<String> labels) {
        List<String> candidates = labels.isEmpty() ? List.of("科技", "财经", "体育", "教育", "其他") : labels;
        String label = candidates.stream().filter(item -> text.contains(item)).findFirst().orElse(candidates.get(0));
        return Map.of("label", label, "scores", candidates.stream().map(item -> Map.of("label", item, "score", item.equals(label) ? 0.82 : 0.18 / Math.max(1, candidates.size() - 1))).toList());
    }

    private Map<String, Object> sentimentDemo(String text) {
        long positive = POSITIVE_WORDS.stream().filter(text::contains).count();
        long negative = NEGATIVE_WORDS.stream().filter(text::contains).count();
        String label = positive == negative ? "neutral" : positive > negative ? "positive" : "negative";
        return Map.of("label", label, "score", label.equals("neutral") ? 0.5 : 0.75);
    }

    private Map<String, Object> similarity(String text, String textPair) {
        Set<String> left = new HashSet<>(tokens(text, true));
        Set<String> right = new HashSet<>(tokens(textPair, true));
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        double score = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
        return Map.of("score", Math.round(score * 10000.0) / 10000.0, "shared", intersection);
    }

    private Map<String, Object> styleTransfer(String text, String style) {
        String output = "concise".equals(style) ? abstractiveSummary(text, 50).get("summary").toString() : text.replace("我觉得", "经分析认为").replace("挺", "较为");
        return Map.of("style", style, "output", output);
    }

    private List<String> tokens(String text, boolean coarse) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            if (coarse && value.codePoints().allMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN) && value.length() > 4) {
                for (int i = 0; i < value.length(); i += 2) out.add(value.substring(i, Math.min(value.length(), i + 2)));
            } else {
                out.add(value);
            }
        }
        return out;
    }

    private List<String> sentences(String text) {
        return Pattern.compile("(?<=[。！？!?\\.])").splitAsStream(text.strip()).filter(item -> !item.isBlank()).toList();
    }

    private String guessPos(String token) {
        if (token.matches("\\d+.*")) return "NUM";
        if (token.matches("[，。！？,.!?；;：:]+")) return "PUNCT";
        if (token.endsWith("了") || token.endsWith("着") || token.endsWith("ing") || token.equalsIgnoreCase("is")) return "VERB";
        if (token.endsWith("的") || token.endsWith("able")) return "ADJ";
        return "NOUN";
    }

    private void addEntityMatches(String text, List<Map<String, Object>> entities, String type, List<String> words) {
        for (String word : words) {
            int index = text.indexOf(word);
            while (index >= 0) {
                entities.add(entity(type, word, index, index + word.length()));
                index = text.indexOf(word, index + word.length());
            }
        }
    }

    private Map<String, Object> entity(String type, String text, int start, int end) {
        return Map.of("type", type, "text", text, "start", start, "end", end);
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private boolean boolValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(Object::toString).filter(item -> !item.isBlank()).toList();
        if (value instanceof String text && !text.isBlank()) return List.of(text.split("\\s*,\\s*"));
        return List.of();
    }
}
