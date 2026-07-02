package com.xnlp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelSource;
import com.xnlp.core.config.ModelType;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime descriptor for a registered model.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelInfo {

    private String name;
    private ModelType type;
    private ModelProtocol protocol;
    private ModelSource source;
    private String version;
    private String backend;
    private String provider;
    private String modelName;
    private String baseUrl;
    private boolean apiKeySet;
    private String device;
    private String status;
    private Instant loadedAt;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public static ModelInfo fromConfig(ModelConfig cfg) {
        ModelInfo info = new ModelInfo();
        info.name = cfg.getName();
        info.type = cfg.getType();
        info.protocol = cfg.getProtocol();
        info.source = cfg.getSource();
        info.version = cfg.getVersion();
        info.backend = cfg.getBackend();
        info.provider = cfg.getProvider() != null ? cfg.getProvider() : cfg.getBackend();
        info.modelName = cfg.getModelName();
        info.baseUrl = cfg.getBaseUrl();
        info.apiKeySet = cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
        info.device = cfg.getDevice();
        return info;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ModelType getType() { return type; }
    public void setType(ModelType type) { this.type = type; }
    public ModelProtocol getProtocol() { return protocol; }
    public void setProtocol(ModelProtocol protocol) { this.protocol = protocol; }
    public ModelSource getSource() { return source; }
    public void setSource(ModelSource source) { this.source = source; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isApiKeySet() { return apiKeySet; }
    public void setApiKeySet(boolean apiKeySet) { this.apiKeySet = apiKeySet; }
    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLoadedAt() { return loadedAt; }
    public void setLoadedAt(Instant loadedAt) { this.loadedAt = loadedAt; }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = new LinkedHashMap<>(metadata); }
}
