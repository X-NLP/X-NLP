package com.xnlp.server.config;

import com.xnlp.core.registry.ModelRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.model.ChatModel;
import java.util.Map;

/**
 * Wires the X-NLP ModelRegistry with Spring AI ChatModel beans.
 *
 * <p>ChatModel instances are auto-configured by Spring AI starters
 * (e.g. {@code spring-ai-ollama-starter}, {@code spring-ai-openai-starter}).
 * This class collects them and registers each one in the ModelRegistry,
 * keyed by its bean name (e.g. {@code ollamaChatModel} maps to {@code ollama}).
 */
@Configuration
@EnableConfigurationProperties(XNLPProperties.class)
public class XNLPConfiguration {

    private static final Logger log = LoggerFactory.getLogger(XNLPConfiguration.class);

    private ModelRegistry registry;

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry(Map<String, ChatModel> chatModelBeans) {
        ModelRegistry reg = new ModelRegistry();
        for (var entry : chatModelBeans.entrySet()) {
            String beanName = entry.getKey();
            ChatModel cm = entry.getValue();
            String provider = deriveProvider(beanName, cm);
            log.info("Discovered ChatModel bean: {} -> provider={}", beanName, provider);
        }
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

    private static String deriveProvider(String beanName, ChatModel cm) {
        // Common Spring AI bean names: ollamaChatModel, openAiChatModel, vertexAiChatModel
        String lower = beanName.toLowerCase();
        if (lower.contains("ollama")) return "ollama";
        if (lower.contains("openai")) return "openai";
        if (lower.contains("vertex")) return "vertex";
        // Fallback: use the simple class name
        return cm.getClass().getSimpleName();
    }
}
