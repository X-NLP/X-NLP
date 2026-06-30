package com.xnlp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inference response payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PredictResponse {

    private String text;
    private String model;
    private double elapsedSeconds;
    private List<Float> logprobs;
    private List<TokenInfo> tokens;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getElapsedSeconds() { return elapsedSeconds; }
    public void setElapsedSeconds(double elapsedSeconds) { this.elapsedSeconds = elapsedSeconds; }
    public List<Float> getLogprobs() { return logprobs != null ? Collections.unmodifiableList(logprobs) : null; }
    public void setLogprobs(List<Float> logprobs) { this.logprobs = logprobs != null ? new ArrayList<>(logprobs) : null; }
    public List<TokenInfo> getTokens() { return tokens != null ? Collections.unmodifiableList(tokens) : null; }
    public void setTokens(List<TokenInfo> tokens) { this.tokens = tokens != null ? new ArrayList<>(tokens) : null; }

    public static PredictResponse ok(String text, String model, double elapsed) {
        PredictResponse r = new PredictResponse();
        r.text = text;
        r.model = model;
        r.elapsedSeconds = elapsed;
        return r;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TokenInfo {
        private String token;
        private float logprob;
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public float getLogprob() { return logprob; }
        public void setLogprob(float logprob) { this.logprob = logprob; }
    }
}
