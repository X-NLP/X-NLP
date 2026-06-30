package com.xnlp.server.config;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code xnlp.*} from application.yml to typed config objects.
 */
@ConfigurationProperties(prefix = "xnlp")
public class XNLPProperties {

    private ServerConfig server = new ServerConfig();
    private List<ModelConfig> models = new ArrayList<>();

    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }
    public List<ModelConfig> getModels() { return models; }
    public void setModels(List<ModelConfig> models) { this.models = models; }
}
