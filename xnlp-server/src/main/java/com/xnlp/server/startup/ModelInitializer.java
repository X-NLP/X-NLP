package com.xnlp.server.startup;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.registry.ModelRegistry;
import com.xnlp.server.service.MetricsService;
import com.xnlp.server.config.XNLPProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the models defined in {@code xnlp.models} config once the application is ready.
 *
 * <p>After startup, Spring AI ChatModel beans are already auto-configured.
 * This initializer registers each configured model name -> ChatModel mapping
 * in the ModelRegistry based on the provider field in the model config.
 */
@Component
public class ModelInitializer {

    private static final Logger log = LoggerFactory.getLogger(ModelInitializer.class);
    private volatile boolean startupComplete = false;

    private final ModelRegistry registry;
    private final MetricsService metrics;
    private final XNLPProperties properties;

    public ModelInitializer(ModelRegistry registry, MetricsService metrics, XNLPProperties properties) {
        this.registry = registry;
        this.metrics = metrics;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        for (ModelConfig cfg : properties.getModels()) {
            try {
                registry.loadModel(cfg);
                metrics.incrementLoadedModels();
                log.info("Auto-loaded model: {} provider={}", cfg.getName(), cfg.getBackend());
            } catch (Exception e) {
                log.error("Failed to auto-load model '{}': {}", cfg.getName(), e.getMessage());
            }
        }
        startupComplete = true;
        log.info("Model initialisation complete. startupComplete flag set.");
    }

    public boolean isStartupComplete() {
        return startupComplete;
    }
}
