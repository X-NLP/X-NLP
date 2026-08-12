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
 * Dependency and semantic dependency parsing.
 */
@Component
public class ParserComponent implements NlpComponent {

    public ParserComponent(CapabilityRegistry registry) {
        registry.register(this);
        registry.register(new SemanticDependencyProxy());
    }

    @Override public String id() { return "DEP"; }
    @Override public String displayName() { return "依存句法分析"; }
    @Override public String description() {
        return "Analyze syntactic or semantic dependency arcs between tokens.";
    }
    @Override public Map<String, String> parameterSchema() {
        return Map.of("language", "zh, en, ja, mul", "semantic", "boolean");
    }

    @Override
    public ComponentResult execute(NlpContext ctx) {
        boolean semantic = ctx.getBool("semantic");
        List<String> tokens = TokenizerComponent.tokenize(ctx.getText(), true);
        List<Map<String, Object>> arcs = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            int head = i == 0 ? -1 : i - 1;
            arcs.add(Map.of("id", i, "token", tokens.get(i), "head", head,
                    "relation", i == 0 ? "root" : semantic ? "semantic-mod" : "dep"));
        }
        return ComponentResult.of(id(), Map.of(
                "nodes", tokens, "arcs", arcs,
                "type", semantic ? "semantic" : "syntactic"));
    }

    private class SemanticDependencyProxy implements NlpComponent {
        @Override public String id() { return "SDP"; }
        @Override public String displayName() { return "语义依存分析"; }
        @Override public String description() { return "Semantic dependency parsing graph."; }
        @Override public Map<String, String> parameterSchema() { return Map.of(); }
        @Override public ComponentResult execute(NlpContext ctx) {
            return ParserComponent.this.execute(
                    NlpContext.builder().text(ctx.getText()).language(ctx.getLanguage())
                            .param("semantic", true).build());
        }
    }
}
