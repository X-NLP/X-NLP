package com.xnlp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inference request payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PredictRequest {

    @NotBlank(message = "text must not be blank")
    @Size(max = 32768, message = "text exceeds maximum length of 32768")
    private String text;

    @JsonProperty("model")
    private String modelName;

    @JsonProperty("max_length")
    private Integer maxLength;

    private Map<String, Object> parameters = new LinkedHashMap<>();

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    public Map<String, Object> getParameters() { return Collections.unmodifiableMap(parameters); }
    public void setParameters(Map<String, Object> parameters) { this.parameters = new LinkedHashMap<>(parameters); }
}
