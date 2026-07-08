package com.xnlp.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelSource;
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
        result.put("providers", providerPresets());
        return result;
    }

    private void normalize(ModelConfig config) {
        if (config.getType() == null) config.setType(ModelType.CHAT);
        if (config.getProtocol() == null) config.setProtocol(defaultProtocol(config.getType()));
        if (config.getSource() == null) config.setSource(ModelSource.CUSTOM);
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
        return protocol != ModelProtocol.SPRING_AI_CHAT
                && protocol != ModelProtocol.SPRING_AI_EMBEDDING
                && protocol != ModelProtocol.LOCAL_JAVA_SPI
                && protocol != ModelProtocol.LOCAL_CLASSIFIER;
    }

    private static ModelProtocol defaultProtocol(ModelType type) {
        return switch (type) {
            case CHAT -> ModelProtocol.SPRING_AI_CHAT;
            case EMBEDDING -> ModelProtocol.SPRING_AI_EMBEDDING;
            case RERANKING -> ModelProtocol.COHERE_RERANK;
            case TOKENIZATION -> ModelProtocol.HANLP_TOKENIZATION;
            case PART_OF_SPEECH -> ModelProtocol.HANLP_POS;
            case NAMED_ENTITY_RECOGNITION -> ModelProtocol.HANLP_NER;
            case DEPENDENCY_PARSING -> ModelProtocol.HANLP_DEPENDENCY;
            case SEMANTIC_ROLE_LABELING -> ModelProtocol.HANLP_SRL;
            case TEXT_CLASSIFICATION -> ModelProtocol.HANLP_CLASSIFICATION;
        };
    }

    private static String defaultProvider(ModelProtocol protocol) {
        String name = protocol.name().toLowerCase();
        if (name.startsWith("openai")) return "openai";
        if (name.startsWith("ollama")) return "ollama";
        if (name.startsWith("anthropic")) return "anthropic";
        if (name.startsWith("google")) return "google";
        if (name.startsWith("cohere")) return "cohere";
        if (name.startsWith("jina")) return "jina";
        if (name.startsWith("hanlp")) return "hanlp";
        if (name.startsWith("local")) return "local";
        return "spring-ai";
    }

    private static List<Map<String, Object>> providerPresets() {
        return List.of(
                provider("openai", "OpenAI", ModelSource.OFFICIAL, "https://api.openai.com/v1", List.of(
                        model("gpt-4o", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 128000, 4096),
                        model("gpt-4o-mini", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 128000, 4096),
                        model("gpt-4.1", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 1047576, 32768),
                        model("gpt-4.1-mini", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 1047576, 32768),
                        model("text-embedding-3-large", ModelType.EMBEDDING, ModelProtocol.OPENAI_EMBEDDINGS, 8191, 0),
                        model("text-embedding-3-small", ModelType.EMBEDDING, ModelProtocol.OPENAI_EMBEDDINGS, 8191, 0)
                )),
                provider("anthropic", "Anthropic", ModelSource.OFFICIAL, "https://api.anthropic.com/v1", List.of(
                        model("claude-3-5-sonnet-latest", ModelType.CHAT, ModelProtocol.ANTHROPIC_MESSAGES, 200000, 8192),
                        model("claude-3-5-haiku-latest", ModelType.CHAT, ModelProtocol.ANTHROPIC_MESSAGES, 200000, 8192),
                        model("claude-3-opus-latest", ModelType.CHAT, ModelProtocol.ANTHROPIC_MESSAGES, 200000, 4096)
                )),
                provider("google", "Google Gemini", ModelSource.OFFICIAL, "https://generativelanguage.googleapis.com/v1beta", List.of(
                        model("gemini-1.5-pro", ModelType.CHAT, ModelProtocol.GOOGLE_GEMINI_GENERATE_CONTENT, 2000000, 8192),
                        model("gemini-1.5-flash", ModelType.CHAT, ModelProtocol.GOOGLE_GEMINI_GENERATE_CONTENT, 1000000, 8192),
                        model("gemini-embedding-001", ModelType.EMBEDDING, ModelProtocol.GOOGLE_GEMINI_EMBEDDING, 2048, 0)
                )),
                provider("ollama", "Ollama", ModelSource.OFFICIAL, "http://localhost:11434", List.of(
                        model("llama3.1", ModelType.CHAT, ModelProtocol.OLLAMA_CHAT, 131072, 4096),
                        model("qwen2.5", ModelType.CHAT, ModelProtocol.OLLAMA_CHAT, 32768, 4096),
                        model("mistral", ModelType.CHAT, ModelProtocol.OLLAMA_CHAT, 32768, 4096),
                        model("deepseek-r1", ModelType.CHAT, ModelProtocol.OLLAMA_CHAT, 32768, 4096),
                        model("nomic-embed-text", ModelType.EMBEDDING, ModelProtocol.OLLAMA_EMBEDDINGS, 8192, 0),
                        model("mxbai-embed-large", ModelType.EMBEDDING, ModelProtocol.OLLAMA_EMBEDDINGS, 512, 0)
                )),
                provider("deepseek", "DeepSeek", ModelSource.OFFICIAL, "https://api.deepseek.com/v1", List.of(
                        model("deepseek-chat", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 64000, 4096),
                        model("deepseek-reasoner", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 64000, 8192)
                )),
                provider("qwen", "Alibaba Qwen", ModelSource.OFFICIAL, "https://dashscope.aliyuncs.com/compatible-mode/v1", List.of(
                        model("qwen-plus", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 131072, 8192),
                        model("qwen-max", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 32768, 8192),
                        model("qwen-turbo", ModelType.CHAT, ModelProtocol.OPENAI_CHAT_COMPLETIONS, 1000000, 8192),
                        model("text-embedding-v3", ModelType.EMBEDDING, ModelProtocol.OPENAI_EMBEDDINGS, 8192, 0)
                )),
                provider("cohere", "Cohere", ModelSource.OFFICIAL, "https://api.cohere.com/v2", List.of(
                        model("rerank-v3.5", ModelType.RERANKING, ModelProtocol.COHERE_RERANK, 4096, 0)
                )),
                provider("jina", "Jina AI", ModelSource.OFFICIAL, "https://api.jina.ai/v1", List.of(
                        model("jina-reranker-v2-base-multilingual", ModelType.RERANKING, ModelProtocol.JINA_RERANK, 8192, 0),
                        model("jina-embeddings-v3", ModelType.EMBEDDING, ModelProtocol.OPENAI_EMBEDDINGS, 8192, 0)
                )),
                provider("hanlp", "HanLP", ModelSource.OFFICIAL, "https://hanlp.hankcs.com/api", List.of(
                        model("coarse-tok", ModelType.TOKENIZATION, ModelProtocol.HANLP_TOKENIZATION, 4096, 0),
                        model("fine-tok", ModelType.TOKENIZATION, ModelProtocol.HANLP_TOKENIZATION, 4096, 0),
                        model("pos", ModelType.PART_OF_SPEECH, ModelProtocol.HANLP_POS, 4096, 0),
                        model("ner-msra", ModelType.NAMED_ENTITY_RECOGNITION, ModelProtocol.HANLP_NER, 4096, 0),
                        model("dep", ModelType.DEPENDENCY_PARSING, ModelProtocol.HANLP_DEPENDENCY, 4096, 0),
                        model("srl", ModelType.SEMANTIC_ROLE_LABELING, ModelProtocol.HANLP_SRL, 4096, 0),
                        model("classifier", ModelType.TEXT_CLASSIFICATION, ModelProtocol.HANLP_CLASSIFICATION, 4096, 0)
                )),
                provider("local", "Local Java SPI", ModelSource.CUSTOM, "", List.of(
                        model("local-tokenizer", ModelType.TOKENIZATION, ModelProtocol.LOCAL_JAVA_SPI, 4096, 0),
                        model("local-classifier", ModelType.TEXT_CLASSIFICATION, ModelProtocol.LOCAL_CLASSIFIER, 4096, 0)
                )),
                provider("custom", "Custom", ModelSource.CUSTOM, "", List.of())
        );
    }

    private static Map<String, Object> provider(String id, String name, ModelSource source,
                                                String baseUrl, List<Map<String, Object>> models) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("id", id);
        provider.put("name", name);
        provider.put("source", source.name());
        provider.put("baseUrl", baseUrl);
        provider.put("models", models);
        return provider;
    }

    private static Map<String, Object> model(String name, ModelType type, ModelProtocol protocol,
                                             int maxInputLength, int maxOutputLength) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("name", name);
        model.put("type", type.name());
        model.put("protocol", protocol.name());
        model.put("maxInputLength", maxInputLength);
        model.put("maxOutputLength", maxOutputLength);
        return model;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
