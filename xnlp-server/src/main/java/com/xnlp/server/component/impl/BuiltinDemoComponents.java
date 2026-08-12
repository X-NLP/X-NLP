package com.xnlp.server.component.impl;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.server.nlp.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Registers the remaining built-in demo NLP components that don't warrant
 * dedicated source files in this MVP phase.
 *
 * <p>Each component is self-contained as a lambda registered against the
 * {@link CapabilityRegistry}.  As implementations mature, individual
 * components can be promoted to their own classes.
 */
@Component
public class BuiltinDemoComponents {

    public BuiltinDemoComponents(CapabilityRegistry registry) {
        registry.register(srl());
        registry.register(constituency());
        registry.register(amr());
        registry.register(keyphrase());
        registry.register(extractiveSummarizer());
        registry.register(summarizer());
        registry.register(correction());
        registry.register(similarity());
        registry.register(styleTransfer());
    }

    private static NlpComponent srl() {
        return simple("SRL", "语义角色标注",
                "Identify predicates and argument roles in a sentence.",
                Map.of("language", "zh, en, ja, mul"),
                ctx -> {
                    List<String> tokens = TokenizerComponent.tokenize(ctx.getText(), true);
                    String pred = tokens.stream()
                            .filter(t -> PosTaggerComponent.guessPos(t).equals("VERB"))
                            .findFirst().orElse(tokens.isEmpty() ? "" : tokens.get(0));
                    List<Map<String, Object>> roles = new ArrayList<>();
                    if (!tokens.isEmpty()) roles.add(Map.of("role", "ARG0", "text", tokens.get(0)));
                    if (tokens.size() > 1) roles.add(Map.of("role", "PRED", "text", pred));
                    if (tokens.size() > 2) roles.add(Map.of("role", "ARG1", "text",
                            String.join("", tokens.subList(2, tokens.size()))));
                    return ComponentResult.of("SRL",
                            Map.of("frames", List.of(Map.of("predicate", pred, "roles", roles))));
                });
    }

    private static NlpComponent constituency() {
        return simple("CON", "成分句法分析",
                "Show sentence constituents as a tree.", Map.of("language", "zh, en"),
                ctx -> {
                    List<String> t = TokenizerComponent.tokenize(ctx.getText(), true);
                    return ComponentResult.of("CON", Map.of("tree", Map.of("label", "S", "children", List.of(
                            Map.of("label", "NP", "text", t.isEmpty() ? "" : t.get(0)),
                            Map.of("label", "VP", "text", t.size() > 1
                                    ? String.join("", t.subList(1, t.size())) : "")))));
                });
    }

    private static NlpComponent amr() {
        return simple("AMR", "抽象意义表示",
                "Represent meaning as AMR graph triples.", Map.of("language", "en, zh"),
                ctx -> {
                    List<String> t = TokenizerComponent.tokenize(ctx.getText(), true);
                    List<Map<String, Object>> triples = new ArrayList<>();
                    for (int i = 0; i < Math.min(t.size(), 8); i++) {
                        triples.add(Map.of("source", "x" + i, "relation",
                                i == 0 ? "instance" : "ARG", "target", t.get(i)));
                    }
                    return ComponentResult.of("AMR", Map.of("triples", triples));
                });
    }

    private static NlpComponent keyphrase() {
        return simple("KEYPHRASE", "关键词短语",
                "Extract key phrases from text.", Map.of("topK", "number"),
                ctx -> {
                    int topK = ctx.getInt("topK", 5);
                    List<String> phrases = TokenizerComponent.tokenize(ctx.getText(), true).stream()
                            .filter(t -> t.length() > 1).distinct()
                            .limit(Math.max(1, topK)).toList();
                    return ComponentResult.of("KEYPHRASE", Map.of("keyphrases", phrases));
                });
    }

    private static NlpComponent extractiveSummarizer() {
        return simple("EXSUM", "抽取式摘要",
                "Select important source sentences to form an extractive summary.",
                Map.of("maxLength", "number"),
                ctx -> {
                    int maxLen = ctx.getInt("maxLength", 80);
                    String normalized = ctx.getText().replaceAll("\\s+", " ").strip();
                    if (normalized.isBlank()) {
                        return ComponentResult.of("EXSUM", Map.of("summary", "", "sentences", List.of()));
                    }
                    List<String> sentences = Arrays.stream(normalized.split("(?<=[。！？.!?])\\s*"))
                            .map(String::strip)
                            .filter(item -> !item.isBlank())
                            .toList();
                    String summary = sentences.stream()
                            .sorted(Comparator.comparingInt(String::length).reversed())
                            .limit(3)
                            .collect(java.util.stream.Collectors.joining(" "));
                    if (summary.length() > maxLen) {
                        summary = summary.substring(0, Math.max(1, maxLen)) + "...";
                    }
                    return ComponentResult.of("EXSUM", Map.of(
                            "summary", summary,
                            "sentences", sentences.stream().limit(3).toList()));
                });
    }

    private static NlpComponent summarizer() {
        return simple("ABSUM", "生成式摘要",
                "Generate a compressed summary of the input text.",
                Map.of("maxLength", "number"),
                ctx -> {
                    int maxLen = ctx.getInt("maxLength", 80);
                    String normalized = ctx.getText().replaceAll("\\s+", " ").strip();
                    String summary = normalized.length() <= maxLen
                            ? normalized
                            : normalized.substring(0, Math.max(1, maxLen)) + "...";
                    return ComponentResult.of("ABSUM",
                            Map.of("summary", summary, "maxLength", maxLen));
                });
    }

    private static NlpComponent correction() {
        return simple("COR", "文本纠错",
                "Detect and correct common text normalization issues.",
                Map.of("language", "zh, en"),
                ctx -> {
                    String text = ctx.getText();
                    String corrected = text.replace("的的", "的").replace("地地", "地")
                            .replaceAll("\\s+", " ").strip();
                    List<Map<String, Object>> edits = new ArrayList<>();
                    if (!corrected.equals(text)) edits.add(Map.of(
                            "from", text, "to", corrected, "type", "normalization"));
                    return ComponentResult.of("COR",
                            Map.of("corrected", corrected, "edits", edits));
                });
    }

    private static NlpComponent similarity() {
        return simple("STS", "语义相似度",
                "Score text similarity using Jaccard on tokens.",
                Map.of("textPair", "string"),
                ctx -> {
                    Set<String> left = new HashSet<>(TokenizerComponent.tokenize(ctx.getText(), true));
                    Set<String> right = new HashSet<>(TokenizerComponent.tokenize(ctx.getTextPair(), true));
                    Set<String> inter = new HashSet<>(left); inter.retainAll(right);
                    Set<String> union = new HashSet<>(left); union.addAll(right);
                    double score = union.isEmpty() ? 0 : (double) inter.size() / union.size();
                    return ComponentResult.of("STS", Map.of(
                            "score", Math.round(score * 10000.0) / 10000.0,
                            "shared", inter));
                });
    }

    private static NlpComponent styleTransfer() {
        return simple("TST", "文本风格转换",
                "Convert text to a formal or concise style.",
                Map.of("style", "formal or concise"),
                ctx -> {
                    String style = ctx.getString("style", "formal");
                    String output = "concise".equals(style)
                            ? ctx.getText().replaceAll("\\s+", " ").strip()
                            : ctx.getText().replace("我觉得", "经分析认为").replace("挺", "较为");
                    return ComponentResult.of("TST", Map.of("style", style, "output", output));
                });
    }

    // ---- helpers ----

    @FunctionalInterface
    interface ComponentFn {
        ComponentResult execute(NlpContext ctx);
    }

    private static NlpComponent simple(String id, String name, String desc,
                                        Map<String, String> params, ComponentFn fn) {
        return new NlpComponent() {
            @Override public String id() { return id; }
            @Override public String displayName() { return name; }
            @Override public String description() { return desc; }
            @Override public Map<String, String> parameterSchema() { return params; }
            @Override public ComponentResult execute(NlpContext ctx) { return fn.execute(ctx); }
        };
    }
}
