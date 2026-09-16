package com.xnlp.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** Validated request for an asynchronous retrieval evaluation run. */
public record RetrievalEvaluationCreateRequest(
        @Size(max = 120, message = "datasetId must not exceed 120 characters")
        String datasetId,
        @Size(max = 1_000, message = "samples must not contain more than 1000 items")
        List<@Valid RetrievalEvaluationSampleRequest> samples,
        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 100, message = "topK must not exceed 100")
        Integer topK,
        @DecimalMin(value = "0.0", message = "minScore must be at least 0")
        @DecimalMax(value = "1.0", message = "minScore must not exceed 1")
        Double minScore,
        @Size(max = 50, message = "filter must not contain more than 50 values")
        Map<String, Object> filter,
        Boolean rerank,
        @Min(value = 1, message = "rerankTopN must be at least 1")
        @Max(value = 100, message = "rerankTopN must not exceed 100")
        Integer rerankTopN) {

    public RetrievalEvaluationCreateRequest {
        if (topK == null) {
            topK = 10;
        }
        if (rerank == null) {
            rerank = false;
        }
        if (rerankTopN == null) {
            rerankTopN = Math.min(topK, 10);
        }
    }

    @AssertTrue(message = "provide exactly one of datasetId or samples")
    public boolean isInputSourceValid() {
        boolean hasDataset = datasetId != null && !datasetId.isBlank();
        boolean hasSamples = samples != null && !samples.isEmpty();
        return hasDataset != hasSamples;
    }

    @AssertTrue(message = "rerankTopN must not exceed topK")
    public boolean isRerankTopNValid() {
        return rerankTopN == null || topK == null || rerankTopN <= topK;
    }
}
