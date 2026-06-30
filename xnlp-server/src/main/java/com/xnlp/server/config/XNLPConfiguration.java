package com.xnlp.server.config;

import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.backend.SimpleDJLBackend;
import com.xnlp.server.backend.SimpleONNXBackend;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(XNLPProperties.class)
public class XNLPConfiguration {

    private static final Logger log = LoggerFactory.getLogger(XNLPConfiguration.class);

    private ModelRegistry registry;

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry() {
        ModelRegistry reg = new ModelRegistry();
        reg.registerBackend(new SimpleONNXBackend());
        reg.registerBackend(new SimpleDJLBackend());
        this.registry = reg;
        return reg;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down X-NLP registry...");
        if (registry != null) {
            registry.shutdown();
        }
    }
}
