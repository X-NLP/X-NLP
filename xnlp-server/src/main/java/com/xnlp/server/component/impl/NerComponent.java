package com.xnlp.server.component.impl;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.server.nlp.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Named entity recognition using pattern-based demo matching.
 */
@Component
public class NerComponent implements NlpComponent {

    public NerComponent(CapabilityRegistry registry) { registry.register(this); }

    @Override public String id() { return "NER"; }
    @Override public String displayName() { return "命名实体识别"; }
    @Override public String description() {
        return "Extract named entities: persons, organizations, locations, dates, and numbers.";
    }
    @Override public Map<String, String> parameterSchema() {
        return Map.of("language", "zh, en, ja, mul");
    }

    @Override
    public ComponentResult execute(NlpContext ctx) {
        String text = ctx.getText();
        List<Map<String, Object>> entities = new ArrayList<>();
        addMatches(text, entities, "PERSON", List.of("先生", "女士", "教授", "记者"));
        addMatches(text, entities, "LOCATION", List.of("北京", "上海", "深圳", "广州", "中国", "美国", "日本", "伊拉克", "剑桥"));
        addMatches(text, entities, "ORGANIZATION", List.of("公司", "大学", "委员会", "HanLP", "X-NLP", "联合国"));
        var numMatcher = Pattern.compile("\\d+(?:年|月|日|%|\\.\\d+)?").matcher(text);
        while (numMatcher.find()) {
            entities.add(Map.of("type", "NUMBER", "text", numMatcher.group(),
                    "start", numMatcher.start(), "end", numMatcher.end()));
        }
        entities.sort(Comparator.comparingInt(e -> ((Number) e.get("start")).intValue()));
        return ComponentResult.of(id(), Map.of("entities", entities));
    }

    private static void addMatches(String text, List<Map<String, Object>> out, String type, List<String> words) {
        for (String word : words) {
            int idx = text.indexOf(word);
            while (idx >= 0) {
                out.add(Map.of("type", type, "text", word, "start", idx, "end", idx + word.length()));
                idx = text.indexOf(word, idx + word.length());
            }
        }
    }
}
