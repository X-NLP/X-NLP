package com.xnlp.server.component.impl;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.server.nlp.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Part-of-speech tagging over segmented tokens.
 */
@Component
public class PosTaggerComponent implements NlpComponent {

    public PosTaggerComponent(CapabilityRegistry registry) {
        registry.register(this);
    }

    @Override public String id() { return "POS"; }
    @Override public String displayName() { return "词性标注"; }
    @Override public String description() {
        return "Attach part-of-speech tags to tokenized text. Supports Chinese, English, Japanese, and multilingual.";
    }
    @Override public Map<String, String> parameterSchema() {
        return Map.of("language", "zh, en, ja, mul");
    }

    @Override
    public ComponentResult execute(NlpContext ctx) {
        List<String> tokens = TokenizerComponent.tokenize(ctx.getText(), false);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String token : tokens) {
            rows.add(Map.of("token", token, "pos", guessPos(token)));
        }
        return ComponentResult.of(id(), Map.of("tokens", rows));
    }

    static String guessPos(String token) {
        if (token.matches("\\d+.*")) return "NUM";
        if (token.matches("[，。！？,.!?；;：:]+")) return "PUNCT";
        if (token.endsWith("了") || token.endsWith("着")
                || token.endsWith("ing") || token.equalsIgnoreCase("is"))
            return "VERB";
        if (token.endsWith("的") || token.endsWith("able")) return "ADJ";
        return "NOUN";
    }
}
