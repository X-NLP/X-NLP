package com.xnlp.server.dto;

import com.xnlp.core.eval.NLPTaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request contract for scheduling an evaluation run. */
public record EvaluationCreateRequest(
        @NotBlank(message = "modelName must not be blank")
        @Size(max = 120, message = "modelName must not exceed 120 characters")
        String modelName,
        @NotBlank(message = "datasetId must not be blank")
        @Size(max = 120, message = "datasetId must not exceed 120 characters")
        String datasetId,
        NLPTaskType taskType) {
}
