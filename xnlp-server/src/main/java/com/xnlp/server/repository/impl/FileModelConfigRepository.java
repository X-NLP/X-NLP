package com.xnlp.server.repository.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelSource;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.repository.ModelConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed implementation of {@link ModelConfigRepository}.
 *
 * <p>Each model profile is stored as {@code data/models/<name>.json}.
 */
@Component
@Profile("file")
public class FileModelConfigRepository implements ModelConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(FileModelConfigRepository.class);
    private static final Path STORE_DIR = Paths.get("data", "models");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ModelConfig> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(STORE_DIR);
        try (var stream = Files.list(STORE_DIR)) {
            stream.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                try {
                    ModelConfig config = mapper.readValue(path.toFile(), ModelConfig.class);
                    validate(config);
                    cache.put(config.getName(), config);
                    log.info("Loaded model profile: {} type={} protocol={}",
                            config.getName(), config.getType(), config.getProtocol());
                } catch (Exception e) {
                    log.warn("Failed to load model profile from {}", path, e);
                }
            });
        }
    }

    @Override
    public List<ModelConfig> findAll() {
        return cache.values().stream()
                .sorted(Comparator.comparing(ModelConfig::getName))
                .toList();
    }

    @Override
    public Optional<ModelConfig> findByName(String name) {
        return Optional.ofNullable(cache.get(name));
    }

    @Override
    public ModelConfig save(ModelConfig config) {
        try {
            normalize(config);
            validate(config);
            cache.put(config.getName(), config);
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    STORE_DIR.resolve(config.getName() + ".json").toFile(), config);
            log.info("Saved model profile: {} type={} protocol={}",
                    config.getName(), config.getType(), config.getProtocol());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist model config: " + config.getName(), e);
        }
        return config;
    }

    @Override
    public void deleteByName(String name) {
        try {
            cache.remove(name);
            Files.deleteIfExists(STORE_DIR.resolve(name + ".json"));
            log.info("Deleted model profile: {}", name);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete model profile: " + name, e);
        }
    }

    private void normalize(ModelConfig config) {
        if (config.getType() == null) config.setType(ModelType.CHAT);
        if (config.getProtocol() == null) config.setProtocol(defaultProtocol(config.getType()));
        if (config.getSource() == null) config.setSource(ModelSource.CUSTOM);
        if (config.getProvider() == null || config.getProvider().isBlank()) {
            config.setProvider(defaultProvider(config.getProtocol()));
        }
        if (config.getBackend() == null || config.getBackend().isBlank()
                || "auto".equals(config.getBackend())) {
            config.setBackend(config.getProvider());
        }
        if (config.getModelName() == null || config.getModelName().isBlank()) {
            config.setModelName(config.getModelPath());
        }
        if (config.getModelPath() == null || config.getModelPath().isBlank()) {
            config.setModelPath(config.getModelName());
        }
    }

    private void validate(ModelConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("Model name is required");
        }
        if (config.getModelName() == null || config.getModelName().isBlank()) {
            throw new IllegalArgumentException("modelName is required for " + config.getName());
        }
    }

    private static ModelProtocol defaultProtocol(ModelType type) {
        return switch (type) {
            case CHAT -> ModelProtocol.SPRING_AI_CHAT;
            case EMBEDDING -> ModelProtocol.SPRING_AI_EMBEDDING;
            case RERANKING -> ModelProtocol.COHERE_RERANK;
            default -> ModelProtocol.LOCAL_JAVA_SPI;
        };
    }

    private static String defaultProvider(ModelProtocol protocol) {
        return switch (protocol) {
            case SPRING_AI_CHAT, SPRING_AI_EMBEDDING -> "spring-ai";
            case OPENAI_CHAT_COMPLETIONS, OPENAI_EMBEDDINGS -> "openai";
            case OLLAMA_CHAT, OLLAMA_EMBEDDINGS -> "ollama";
            default -> "local";
        };
    }
}
