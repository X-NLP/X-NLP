package com.xnlp.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
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

    private ModelType type = ModelType.CHAT;

    private ModelProtocol protocol = ModelProtocol.SPRING_AI_CHAT;

    private ModelSource source = ModelSource.CUSTOM;

    /** Provider identifier, for example openai, ollama, cohere, jina, or spring-ai. */
    @NotBlank(message = "provider must not be blank")
    private String provider = "spring-ai";

    /** Public model identifier used by the provider API, for example gpt-4o-mini. */
    @JsonProperty("model_name")
    @JsonAlias("modelName")
    @NotBlank(message = "model_name must not be blank")
    private String modelName;

    /** Provider API base URL. Required for non Spring AI bean-backed models. */
    @JsonProperty("base_url")
    @JsonAlias("baseUrl")
    private String baseUrl;

    /** API key or token. It is persisted server-side but never returned by public DTOs. */
    @JsonProperty("api_key")
    @JsonAlias("apiKey")
    private String apiKey;

    private String version = "latest";

    @JsonProperty("model_path")
    private String modelPath;

    @JsonProperty("backend")
    private String backend = "auto";

    private String device = "cpu";

    @JsonProperty("max_input_length")
    @JsonAlias("maxInputLength")
    private int maxInputLength = 512;

    @JsonProperty("max_output_length")
    @JsonAlias("maxOutputLength")
    private int maxOutputLength = 256;

    private Map<String, Object> options = new LinkedHashMap<>();

    @AssertTrue(message = "protocol must match model type")
    @JsonIgnore
    public boolean isProtocolCompatible() {
        return type != null && protocol != null && protocol.getModelType() == type;
    }

    @AssertTrue(message = "base_url is required unless protocol is SPRING_AI_CHAT or SPRING_AI_EMBEDDING")
    @JsonIgnore
    public boolean isBaseUrlPresentWhenRequired() {
        if (protocol == ModelProtocol.SPRING_AI_CHAT || protocol == ModelProtocol.SPRING_AI_EMBEDDING) {
            return true;
        }
        return baseUrl != null && !baseUrl.isBlank();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ModelType getType() { return type; }
    public void setType(ModelType type) { this.type = type; }
    public ModelProtocol getProtocol() { return protocol; }
    public void setProtocol(ModelProtocol protocol) { this.protocol = protocol; }
    public ModelSource getSource() { return source; }
    public void setSource(ModelSource source) { this.source = source; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
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
    public void setOptions(Map<String, Object> options) {
        this.options = options != null ? new LinkedHashMap<>(options) : new LinkedHashMap<>();
    }
}
