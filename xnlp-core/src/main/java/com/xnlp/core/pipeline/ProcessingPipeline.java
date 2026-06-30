package com.xnlp.core.pipeline;

import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;

/**
 * SPI for pre/post-processing pipelines.
 *
 * <p>Implementations hook into the inference lifecycle to transform input
 * before it reaches the model or to decorate output before it is returned.
 * Pipelines are discovered and applied in priority order (lower numbers
 * run first).
 *
 * <p>Implementations must be thread-safe.
 */
public interface ProcessingPipeline {

    /** Unique name for this pipeline, e.g. "tokenizer", "detokenizer". */
    String name();

    /** Lower numbers run first. Default is 100. */
    default int priority() { return 100; }

    /** Whether this pipeline applies to the given model. */
    default boolean supports(String modelName) { return true; }

    /**
     * Transform the request before it is sent to the engine.
     * Return the original or a modified copy; null means "skip this request".
     */
    default PredictRequest preProcess(PredictRequest request) { return request; }

    /**
     * Transform the response after the engine produces it.
     * Return the original or a modified copy; null means "swallow this response".
     */
    default PredictResponse postProcess(PredictResponse response) { return response; }
}
