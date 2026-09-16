package com.xnlp.server.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Validated semantic retrieval and optional rerank request. */
public record KnowledgeSearchRequest(
        @NotBlank(message = "query must not be blank")
        @Size(max = 20_000, message = "query must not exceed 20000 characters")
        String query,
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

    public KnowledgeSearchRequest {
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

    @jakarta.validation.constraints.AssertTrue(message = "rerankTopN must not exceed topK")
    public boolean isRerankTopNValid() {
        return rerankTopN == null || topK == null || rerankTopN <= topK;
    }
}
