package com.xnlp.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request for translation to the service's target language. */
public record NlpTranslateRequest(
        @Size(max = 120, message = "modelName must not exceed 120 characters") String modelName,
        @NotBlank(message = "text must not be blank")
        @Size(max = 100_000, message = "text must not exceed 100000 characters") String text,
        @Size(max = 32, message = "sourceLanguage must not exceed 32 characters") String sourceLanguage) {
}
