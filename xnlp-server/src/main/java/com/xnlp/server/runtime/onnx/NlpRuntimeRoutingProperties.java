package com.xnlp.server.runtime.onnx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Selects project runtime adapters without changing the REST contract. */
@ConfigurationProperties("xnlp.nlp.runtime")
public class NlpRuntimeRoutingProperties {

    private SentimentRuntimeMode sentimentMode = SentimentRuntimeMode.BUILTIN;

    public SentimentRuntimeMode getSentimentMode() {
        return sentimentMode;
    }

    public void setSentimentMode(SentimentRuntimeMode sentimentMode) {
        if (sentimentMode == null) {
            throw new IllegalArgumentException("sentimentMode must not be null");
        }
        this.sentimentMode = sentimentMode;
    }
}
