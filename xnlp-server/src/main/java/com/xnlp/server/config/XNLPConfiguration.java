package com.xnlp.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.JsonUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the X-NLP ModelRegistry bean.
 * ModelRegistry supports Spring AI ChatModel integration but does not require it.
 */
@Configuration
@EnableConfigurationProperties(XNLPProperties.class)
public class XNLPConfiguration {

    private static final Logger log = LoggerFactory.getLogger(XNLPConfiguration.class);
    private ModelRegistry registry;

    /**
     * Jackson is not exposed as an ObjectMapper bean by the current Spring Boot 4
     * web auto-configuration when the application uses the servlet stack.
     * Reuse the project's configured mapper so JDBC repositories can serialize
     * JSON columns consistently with the REST layer.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonUtils.JACKSON_OBJECT_MAPPER;
    }

    @Bean
    public ModelRegistry modelRegistry() {
        ModelRegistry reg = new ModelRegistry();
        this.registry = reg;
        log.info("X-NLP ModelRegistry initialized");
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
