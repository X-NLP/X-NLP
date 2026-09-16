package com.xnlp.server.dto;

import com.xnlp.core.eval.EvaluationEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Validated dataset entry accepted by create and update operations. */
public record DatasetEntryRequest(
        String id,
        @NotBlank(message = "entry input must not be blank")
        @Size(max = 100_000, message = "entry input must not exceed 100000 characters")
        String input,
        @Size(max = 100_000, message = "expectedOutput must not exceed 100000 characters")
        String expectedOutput,
        @Size(max = 100, message = "labels must not contain more than 100 values")
        Map<String, Object> labels,
        @Size(max = 100, message = "metadata must not contain more than 100 values")
        Map<String, Object> metadata) {

    public EvaluationEntry toModel() {
        EvaluationEntry entry = new EvaluationEntry(id, input, expectedOutput);
        entry.setLabels(labels);
        entry.setMetadata(metadata);
        return entry;
    }
}
