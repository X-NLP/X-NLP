package com.xnlp.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request for zero-shot text classification. */
public record NlpClassificationRequest(
        @Size(max = 120, message = "modelName must not exceed 120 characters") String modelName,
        @NotBlank(message = "text must not be blank")
        @Size(max = 100_000, message = "text must not exceed 100000 characters") String text,
        @NotEmpty(message = "categories must contain at least one value")
        @Size(max = 100, message = "categories must not contain more than 100 values")
        List<@NotBlank(message = "category must not be blank") @Size(max = 120) String> categories) {
}
