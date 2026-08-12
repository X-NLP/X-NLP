package com.xnlp.server.component.impl;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.api.ComponentResult;
import com.xnlp.server.nlp.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenization component that segments text into coarse or fine tokens.
 *
 * <p>Supports Chinese, English, Japanese, and multilingual input.
 */
@Component
public class TokenizerComponent implements NlpComponent {

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\\p{IsHan}]+|[A-Za-z]+(?:[-'][A-Za-z]+)*|\\d+(?:\\.\\d+)?|[^\\s]");

    public TokenizerComponent(CapabilityRegistry registry) {
        registry.register(this);
    }

    @Override public String id() { return "TOK"; }
    @Override public String displayName() { return "分词"; }
    @Override public String description() {
        return "Word segmentation with coarse and fine modes, supporting Chinese, English, Japanese, and multilingual text.";
    }
    @Override public Map<String, String> parameterSchema() {
        return Map.of("language", "zh, en, ja, mul", "coarse", "boolean");
    }

    @Override
    public ComponentResult execute(NlpContext ctx) {
        boolean coarse = ctx.getBool("coarse");
        String text = ctx.getText();
        List<String> tokens = tokenize(text, coarse);
        return ComponentResult.of(id(), Map.of(
                "tokens", tokens,
                "count", tokens.size(),
                "coarse", coarse
        ));
    }

    static List<String> tokenize(String text, boolean coarse) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            if (coarse && value.codePoints().allMatch(
                    cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                    && value.length() > 4) {
                for (int i = 0; i < value.length(); i += 2) {
                    out.add(value.substring(i, Math.min(value.length(), i + 2)));
                }
            } else {
                out.add(value);
            }
        }
        return out;
    }
}
