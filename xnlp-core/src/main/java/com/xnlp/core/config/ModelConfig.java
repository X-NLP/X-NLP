package com.xnlp.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for a single NLP model.
 */
public class ModelConfig {

    @NotBlank(message = "model name must not be blank")
    private String name;

    private String version = "latest";

    @JsonProperty("model_path")
    @NotBlank(message = "model_path must not be blank")
    private String modelPath;

    @JsonProperty("backend")
    private String backend = "auto";

    private String device = "cpu";

    @JsonProperty("max_input_length")
    private int maxInputLength = 512;

    @JsonProperty("max_output_length")
    private int maxOutputLength = 256;

    private Map<String, Object> options = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getModelPath() { return modelPath; }
    public void setModelPath(String modelPath) { this.modelPath = modelPath; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
    public int getMaxInputLength() { return maxInputLength; }
    public void setMaxInputLength(int maxInputLength) { this.maxInputLength = maxInputLength; }
    public int getMaxOutputLength() { return maxOutputLength; }
    public void setMaxOutputLength(int maxOutputLength) { this.maxOutputLength = maxOutputLength; }
    public Map<String, Object> getOptions() { return Collections.unmodifiableMap(options); }
    public void setOptions(Map<String, Object> options) { this.options = new LinkedHashMap<>(options); }
}
