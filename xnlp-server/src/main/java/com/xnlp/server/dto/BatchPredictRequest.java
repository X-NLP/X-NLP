package com.xnlp.server.dto;

import com.xnlp.core.model.PredictRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Request envelope for bounded batch inference. */
public record BatchPredictRequest(
        @NotEmpty(message = "requests must not be empty")
        List<@Valid PredictRequest> requests) {
}
