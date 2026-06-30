package com.xnlp.core.pipeline;

import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;

/**
 * Default pipeline that normalizes input text (trim, collapse whitespace)
 * and strips leading/trailing whitespace from output.
 */
public class TextNormalizerPipeline implements ProcessingPipeline {

    @Override
    public String name() { return "text-normalizer"; }

    @Override
    public int priority() { return 10; }

    @Override
    public PredictRequest preProcess(PredictRequest request) {
        if (request.getText() == null) return request;
        String cleaned = request.getText().trim().replaceAll("\\s+", " ");
        if (cleaned.equals(request.getText())) return request;
        PredictRequest modified = copy(request);
        modified.setText(cleaned);
        return modified;
    }

    @Override
    public PredictResponse postProcess(PredictResponse response) {
        if (response.getText() == null) return response;
        String trimmed = response.getText().trim();
        if (trimmed.equals(response.getText())) return response;
        PredictResponse modified = PredictResponse.ok(trimmed, response.getModel(),
                response.getElapsedSeconds());
        modified.setLogprobs(response.getLogprobs());
        modified.setTokens(response.getTokens());
        return modified;
    }

    private static PredictRequest copy(PredictRequest source) {
        PredictRequest c = new PredictRequest();
        c.setText(source.getText());
        c.setModelName(source.getModelName());
        c.setMaxLength(source.getMaxLength());
        c.setParameters(source.getParameters());
        return c;
    }
}
