package com.xnlp.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** One query and its binary-relevance chunk labels. */
public record RetrievalEvaluationSampleRequest(
        @Size(max = 120, message = "sample id must not exceed 120 characters")
        String id,
        @NotBlank(message = "sample query must not be blank")
        @Size(max = 20_000, message = "sample query must not exceed 20000 characters")
        String query,
        @NotEmpty(message = "relevantChunkIds must not be empty")
        @Size(max = 100, message = "relevantChunkIds must not contain more than 100 values")
        List<@NotBlank(message = "relevant chunk id must not be blank")
                @Size(max = 120, message = "relevant chunk id must not exceed 120 characters") String> relevantChunkIds) {
}
