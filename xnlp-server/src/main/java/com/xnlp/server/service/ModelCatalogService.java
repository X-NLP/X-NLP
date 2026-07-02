package com.xnlp.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.model.ModelInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists standard model connection profiles.
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);
    private static final Path STORE_DIR = Paths.get("data", "models");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ModelConfig> catalog = new ConcurrentHashMap<>();

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(STORE_DIR);
        try (var stream = Files.list(STORE_DIR)) {
            stream.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
                try {
                    ModelConfig config = mapper.readValue(path.toFile(), ModelConfig.class);
                    validate(config);
                    catalog.put(config.getName(), config);
                    log.info("Loaded model profile: {} type={} protocol={}",
                            config.getName(), config.getType(), config.getProtocol());
                } catch (Exception e) {
                    log.warn("Failed to load model profile from {}", path, e);
                }
            });
        }
    }

    public List<ModelInfo> list() {
        return catalog.values().stream()
                .sorted(Comparator.comparing(ModelConfig::getName))
                .map(this::toInfo)
                .toList();
    }

    public Optional<ModelConfig> getConfig(String name) {
        return Optional.ofNullable(catalog.get(name));
    }

    public ModelInfo get(String name) {
        return getConfig(name).map(this::toInfo)
                .orElseThrow(() -> new NoSuchElementException("Model profile not found: " + name));
    }

    public ModelInfo save(ModelConfig config) throws IOException {
        normalize(config);
        validate(config);
        catalog.put(config.getName(), config);
        persist(config);
        log.info("Saved model profile: {} type={} protocol={}",
                config.getName(), config.getType(), config.getProtocol());
        return toInfo(config);
    }

    public void delete(String name) throws IOException {
        catalog.remove(name);
        Files.deleteIfExists(fileFor(name));
        log.info("Deleted model profile: {}", name);
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<ModelType, List<String>> protocolsByType = new EnumMap<>(ModelType.class);
        for (ModelType type : ModelType.values()) {
            protocolsByType.put(type, ModelProtocol.forType(type).stream().map(Enum::name).toList());
        }
        result.put("types", Arrays.stream(ModelType.values()).map(Enum::name).toList());
        result.put("protocolsByType", protocolsByType);
        result.put("requiredFields", Map.of(
                "common", List.of("name", "type", "protocol", "provider", "modelName"),
                "remoteProtocol", List.of("baseUrl"),
                "credential", List.of("apiKey optional, stored server-side and never returned")
        ));
        return result;
    }

    private void normalize(ModelConfig config) {
        if (config.getType() == null) config.setType(ModelType.CHAT);
        if (config.getProtocol() == null) config.setProtocol(defaultProtocol(config.getType()));
        if (config.getProvider() == null || config.getProvider().isBlank()) {
            config.setProvider(defaultProvider(config.getProtocol()));
        }
        if (config.getBackend() == null || config.getBackend().isBlank() || "auto".equals(config.getBackend())) {
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
        List<String> errors = new ArrayList<>();
        if (isBlank(config.getName())) errors.add("name is required");
        if (config.getType() == null) errors.add("type is required");
        if (config.getProtocol() == null) errors.add("protocol is required");
        if (isBlank(config.getProvider())) errors.add("provider is required");
        if (isBlank(config.getModelName())) errors.add("modelName is required");
        if (config.getType() != null && config.getProtocol() != null
                && config.getProtocol().getModelType() != config.getType()) {
            errors.add("protocol " + config.getProtocol() + " is not valid for type " + config.getType());
        }
        if (requiresBaseUrl(config.getProtocol()) && isBlank(config.getBaseUrl())) {
            errors.add("baseUrl is required for protocol " + config.getProtocol());
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private ModelInfo toInfo(ModelConfig config) {
        ModelInfo info = ModelInfo.fromConfig(config);
        info.setStatus("configured");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("maxInputLength", config.getMaxInputLength());
        metadata.put("maxOutputLength", config.getMaxOutputLength());
        metadata.put("options", config.getOptions());
        info.setMetadata(metadata);
        return info;
    }

    private void persist(ModelConfig config) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(fileFor(config.getName()).toFile(), config);
    }

    private Path fileFor(String name) {
        return STORE_DIR.resolve(name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
    }

    private static boolean requiresBaseUrl(ModelProtocol protocol) {
        return protocol != ModelProtocol.SPRING_AI_CHAT && protocol != ModelProtocol.SPRING_AI_EMBEDDING;
    }

    private static ModelProtocol defaultProtocol(ModelType type) {
        return switch (type) {
            case CHAT -> ModelProtocol.SPRING_AI_CHAT;
            case EMBEDDING -> ModelProtocol.SPRING_AI_EMBEDDING;
            case RERANKING -> ModelProtocol.COHERE_RERANK;
        };
    }

    private static String defaultProvider(ModelProtocol protocol) {
        String name = protocol.name().toLowerCase();
        if (name.startsWith("openai")) return "openai";
        if (name.startsWith("ollama")) return "ollama";
        if (name.startsWith("cohere")) return "cohere";
        if (name.startsWith("jina")) return "jina";
        return "spring-ai";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
