package com.xnlp.core.engine;

import com.xnlp.core.model.PredictRequest;
import com.xnlp.core.model.PredictResponse;
import java.util.List;

public interface InferenceEngine extends AutoCloseable {

    String backendName();

    boolean supports(String modelPath);

    void load(String modelName, String modelPath,
              java.util.Map<String, Object> options);

    void unload(String modelName);

    boolean isLoaded(String modelName);

    PredictResponse predict(PredictRequest request);

    /**
     * Batch prediction with a default sequential implementation.
     * Engines should override this for true batching.
     */
    default List<PredictResponse> predictBatch(List<PredictRequest> requests) {
        return requests.stream().map(this::predict).toList();
    }

    @Override
    void close();
}
