package com.xnlp.server.component.impl;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.server.nlp.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Sentiment analysis component (positive / negative / neutral).
 */
@Component
public class SentimentComponent implements NlpComponent {

    private static final Set<String> POSITIVE = Set.of("好", "优秀", "喜欢", "满意", "高兴", "成功", "提升",
            "positive", "good", "great", "excellent", "love");
    private static final Set<String> NEGATIVE = Set.of("差", "糟糕", "讨厌", "失败", "问题", "风险", "不好",
            "negative", "bad", "poor", "fail", "risk");

    public SentimentComponent(CapabilityRegistry registry) { registry.register(this); }

    @Override public String id() { return "SENTIMENT"; }
    @Override public String displayName() { return "情感分析"; }
    @Override public String description() {
        return "Classify text sentiment as positive, negative, or neutral.";
    }
    @Override public Map<String, String> parameterSchema() {
        return Map.of("labels", "positive, negative, neutral");
    }

    @Override
    public ComponentResult execute(NlpContext ctx) {
        String text = ctx.getText().toLowerCase();
        long pos = POSITIVE.stream().filter(text::contains).count();
        long neg = NEGATIVE.stream().filter(text::contains).count();
        String label = pos == neg ? "neutral" : pos > neg ? "positive" : "negative";
        double score = "neutral".equals(label) ? 0.5 : 0.75;
        return ComponentResult.of(id(), Map.of("label", label, "score", score));
    }
}
