package com.xnlp.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/** Request for executing an ordered NLP capability pipeline and collecting a trace. */
public record PipelineExecuteRequest(
        @NotBlank String text,
        String textPair,
        String language,
        Map<String, Object> parameters,
        @NotEmpty List<@Valid NodeRequest> nodes) {

    public record NodeRequest(
            @NotBlank String id,
            @NotBlank String capability,
            String name,
            Map<String, Object> parameters) {
    }
}
