package com.xnlp.server.component.impl;

import com.xnlp.core.api.NlpContext;
import com.xnlp.server.nlp.CapabilityRegistry;
import com.xnlp.server.nlp.NlpComponentExecution;
import com.xnlp.server.nlp.RuntimeAwareNlpComponent;
import com.xnlp.server.runtime.onnx.SentimentRuntimeRouter;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Sentiment analysis component routed to builtin or ONNX runtime by configuration. */
@Component
public class SentimentComponent implements RuntimeAwareNlpComponent {

    private final SentimentRuntimeRouter runtimeRouter;

    public SentimentComponent(CapabilityRegistry registry, SentimentRuntimeRouter runtimeRouter) {
        this.runtimeRouter = runtimeRouter;
        registry.register(this);
    }

    @Override
    public String id() {
        return "SENTIMENT";
    }

    @Override
    public String displayName() {
        return "情感分析";
    }

    @Override
    public String description() {
        return "Classify text sentiment as positive, negative, or neutral.";
    }

    @Override
    public Map<String, String> parameterSchema() {
        return Map.of("labels", "positive, negative, neutral");
    }

    @Override
    public NlpComponentExecution executeWithRuntime(NlpContext context) {
        return runtimeRouter.execute(context);
    }
}
