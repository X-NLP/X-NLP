package com.xnlp.server.component.impl;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.server.nlp.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ClassifierComponent implements NlpComponent {

    public ClassifierComponent(CapabilityRegistry registry) { registry.register(this); }

    @Override public String id() { return "CLASSIFICATION"; }
    @Override public String displayName() { return "文本分类"; }
    @Override public String description() { return "Classify text into predefined categories using keyword matching."; }
    @Override public Map<String, String> parameterSchema() { return Map.of("labels", "array of category names"); }

    @Override @SuppressWarnings("unchecked")
    public ComponentResult execute(NlpContext ctx) {
        List<String> candidates = resolveLabels(ctx.getParameter("labels"));
        if (candidates.isEmpty()) candidates = List.of("科技", "财经", "体育", "教育", "其他");
        String text = ctx.getText();
        String label = candidates.stream().filter(text::contains).findFirst().orElse(candidates.get(0));
        int size = Math.max(1, candidates.size());
        List<Map<String, Object>> scores = candidates.stream().map(item ->
                Map.of("label", (Object) item, "score",
                        item.equals(label) ? 0.82 : 0.18 / (size - 1))).toList();
        return ComponentResult.of(id(), Map.of("label", label, "scores", scores));
    }

    @SuppressWarnings("unchecked")
    private static List<String> resolveLabels(Object value) {
        if (value instanceof List<?> list) return list.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        if (value instanceof String s && !s.isBlank()) return List.of(s.split("\\s*,\\s*"));
        return List.of();
    }
}
