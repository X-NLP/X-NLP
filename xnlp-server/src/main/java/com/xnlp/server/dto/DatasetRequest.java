package com.xnlp.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.NLPTaskType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Validated create/update contract for evaluation datasets. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatasetRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 120, message = "name must not exceed 120 characters")
        String name,
        @Size(max = 2_000, message = "description must not exceed 2000 characters")
        String description,
        @NotNull(message = "taskType must be specified")
        NLPTaskType taskType,
        @Size(max = 10_000, message = "entries must not contain more than 10000 items")
        List<@Valid DatasetEntryRequest> entries) {

    public EvaluationDataset toModel() {
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setName(name);
        dataset.setDescription(description);
        dataset.setTaskType(taskType);
        dataset.setEntries(entries == null ? List.of() : entries.stream()
                .map(DatasetEntryRequest::toModel)
                .toList());
        return dataset;
    }
}
