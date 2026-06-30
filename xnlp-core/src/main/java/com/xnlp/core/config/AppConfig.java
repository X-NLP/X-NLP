package com.xnlp.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Root application configuration, composed of server and model settings.
 */
public class AppConfig {

    private ServerConfig server = new ServerConfig();
    @JsonProperty("models")
    private List<ModelConfig> models = new ArrayList<>();

    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }
    public List<ModelConfig> getModels() { return Collections.unmodifiableList(models); }
    public void setModels(List<ModelConfig> models) { this.models = new ArrayList<>(models); }
}
