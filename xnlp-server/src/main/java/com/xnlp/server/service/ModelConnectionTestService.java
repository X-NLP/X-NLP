package com.xnlp.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;
import com.xnlp.server.dto.ModelTestRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelConnectionTestService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Map<String, Object> test(ModelConfig config, ModelTestRequest request) {
        long t0 = System.nanoTime();
        try {
            if (isNlpComponent(config.getType())) {
                return configuredOnly(config, t0);
            }
            if (requiresApiKey(config) && isBlank(config.getApiKey())) {
                return failed(config, "API key is required for " + config.getProtocol(), null, t0);
            }
            HttpResponse<String> response = send(config, request);
            Map<String, Object> body = parseBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failed(config, responseMessage(body, response.body()), response.statusCode(), t0);
            }
            return success(config, extractResult(config, body), response.statusCode(), t0);
        } catch (Exception e) {
            return failed(config, e.getMessage(), null, t0);
        }
    }

    private HttpResponse<String> send(ModelConfig config, ModelTestRequest request)
            throws IOException, InterruptedException {
        return switch (config.getProtocol()) {
            case OPENAI_CHAT_COMPLETIONS -> postJson(
                    openAiEndpoint(config, "/chat/completions"), bearer(config),
                    Map.of("model", config.getModelName(),
                            "messages", List.of(Map.of("role", "user", "content", input(request))),
                            "max_tokens", maxOutput(config)));
            case OPENAI_EMBEDDINGS -> postJson(
                    openAiEndpoint(config, "/embeddings"), bearer(config),
                    Map.of("model", config.getModelName(), "input", input(request)));
            case OLLAMA_CHAT -> postJson(
                    endpoint(config.getBaseUrl(), "/api/chat"), Map.of(),
                    Map.of("model", config.getModelName(),
                            "messages", List.of(Map.of("role", "user", "content", input(request))),
                            "stream", false));
            case OLLAMA_EMBEDDINGS -> postJson(
                    endpoint(config.getBaseUrl(), "/api/embed"), Map.of(),
                    Map.of("model", config.getModelName(), "input", input(request)));
            case ANTHROPIC_MESSAGES -> postJson(
                    endpoint(config.getBaseUrl(), "/messages"),
                    Map.of("x-api-key", config.getApiKey(), "anthropic-version", "2023-06-01"),
                    Map.of("model", config.getModelName(),
                            "max_tokens", maxOutput(config),
                            "messages", List.of(Map.of("role", "user", "content", input(request)))));
            case GOOGLE_GEMINI_GENERATE_CONTENT -> postJson(
                    geminiEndpoint(config, ":generateContent"), Map.of(),
                    Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", input(request)))))));
            case GOOGLE_GEMINI_EMBEDDING -> postJson(
                    geminiEndpoint(config, ":embedContent"), Map.of(),
                    Map.of("model", "models/" + config.getModelName(),
                            "content", Map.of("parts", List.of(Map.of("text", input(request))))));
            case COHERE_RERANK -> postJson(
                    endpoint(config.getBaseUrl(), "/rerank"), bearer(config),
                    Map.of("model", config.getModelName(),
                            "query", query(request),
                            "documents", documents(request),
                            "top_n", Math.min(3, documents(request).size())));
            case JINA_RERANK -> postJson(
                    endpoint(config.getBaseUrl(), "/rerank"), bearer(config),
                    Map.of("model", config.getModelName(),
                            "query", query(request),
                            "documents", documents(request),
                            "top_n", Math.min(3, documents(request).size())));
            case SPRING_AI_CHAT, SPRING_AI_EMBEDDING -> throw new IllegalStateException(
                    config.getProtocol() + " requires an activated Spring AI runtime bean.");
            case HANLP_TOKENIZATION, HANLP_POS, HANLP_NER, HANLP_DEPENDENCY, HANLP_SRL,
                    HANLP_CLASSIFICATION, LOCAL_JAVA_SPI, LOCAL_CLASSIFIER -> throw new IllegalStateException(
                    config.getProtocol() + " is a configured NLP component; runtime execution is not wired yet.");
        };
    }

    private HttpResponse<String> postJson(String url, Map<String, String> headers, Object body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> success(ModelConfig config, Map<String, Object> result,
                                        int httpStatus, long startedAt) {
        Map<String, Object> response = base(config, "succeeded", startedAt);
        response.put("httpStatus", httpStatus);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> failed(ModelConfig config, String message,
                                       Integer httpStatus, long startedAt) {
        Map<String, Object> response = base(config, "failed", startedAt);
        if (httpStatus != null) response.put("httpStatus", httpStatus);
        response.put("message", message != null ? message : "Connection test failed");
        return response;
    }

    private Map<String, Object> configuredOnly(ModelConfig config, long startedAt) {
        Map<String, Object> response = base(config, "configured", startedAt);
        response.put("runtimeReady", false);
        response.put("message", "NLP component profile is valid. Runtime execution will be wired in the NLP pipeline phase.");
        return response;
    }

    private Map<String, Object> base(ModelConfig config, String status, long startedAt) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("type", config.getType());
        response.put("protocol", config.getProtocol());
        response.put("provider", config.getProvider());
        response.put("model", config.getModelName());
        response.put("elapsedSeconds", (System.nanoTime() - startedAt) / 1_000_000_000.0);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractResult(ModelConfig config, Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        switch (config.getProtocol()) {
            case OPENAI_CHAT_COMPLETIONS -> {
                List<Object> choices = (List<Object>) body.getOrDefault("choices", List.of());
                if (!choices.isEmpty()) {
                    Map<String, Object> choice = (Map<String, Object>) choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    result.put("output", message != null ? message.get("content") : null);
                }
            }
            case ANTHROPIC_MESSAGES -> result.put("output", body.get("content"));
            case GOOGLE_GEMINI_GENERATE_CONTENT -> result.put("output", body.get("candidates"));
            case OLLAMA_CHAT -> {
                Map<String, Object> message = (Map<String, Object>) body.get("message");
                result.put("output", message != null ? message.get("content") : body.get("response"));
            }
            case OPENAI_EMBEDDINGS -> result.put("embeddingCount", listSize(body.get("data")));
            case OLLAMA_EMBEDDINGS -> result.put("embeddingCount", listSize(body.get("embeddings")));
            case GOOGLE_GEMINI_EMBEDDING -> result.put("embedding", body.get("embedding"));
            case COHERE_RERANK, JINA_RERANK -> result.put("resultCount", listSize(body.get("results")));
            case SPRING_AI_CHAT, SPRING_AI_EMBEDDING, HANLP_TOKENIZATION, HANLP_POS, HANLP_NER,
                    HANLP_DEPENDENCY, HANLP_SRL, HANLP_CLASSIFICATION, LOCAL_JAVA_SPI,
                    LOCAL_CLASSIFIER -> result.put("output", body);
        }
        if (result.isEmpty()) result.put("raw", body);
        return result;
    }

    private Map<String, Object> parseBody(String body) throws IOException {
        if (body == null || body.isBlank()) return Map.of();
        return mapper.readValue(body, MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private String responseMessage(Map<String, Object> body, String raw) {
        Object error = body.get("error");
        if (error instanceof Map<?, ?> map) {
            Object message = ((Map<String, Object>) map).get("message");
            if (message != null) return message.toString();
        }
        if (error != null) return error.toString();
        return raw != null && !raw.isBlank() ? raw : "Provider returned an error";
    }

    private String openAiEndpoint(ModelConfig config, String suffix) {
        String base = trimSlash(config.getBaseUrl());
        if (!base.endsWith("/v1")) base += "/v1";
        return base + suffix;
    }

    private String geminiEndpoint(ModelConfig config, String action) {
        String model = URLEncoder.encode(config.getModelName(), StandardCharsets.UTF_8);
        String key = URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8);
        return endpoint(config.getBaseUrl(), "/models/" + model + action) + "?key=" + key;
    }

    private String endpoint(String baseUrl, String suffix) {
        return trimSlash(baseUrl) + suffix;
    }

    private String trimSlash(String value) {
        if (isBlank(value)) throw new IllegalArgumentException("baseUrl is required");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Map<String, String> bearer(ModelConfig config) {
        return Map.of("Authorization", "Bearer " + config.getApiKey());
    }

    private int maxOutput(ModelConfig config) {
        return config.getMaxOutputLength() > 0 ? Math.min(config.getMaxOutputLength(), 256) : 256;
    }

    private String input(ModelTestRequest request) {
        return isBlank(request.getInput()) ? "Hello X-NLP" : request.getInput();
    }

    private String query(ModelTestRequest request) {
        if (!isBlank(request.getQuery())) return request.getQuery();
        return input(request);
    }

    private List<String> documents(ModelTestRequest request) {
        if (request.getDocuments() != null && !request.getDocuments().isEmpty()) {
            return request.getDocuments();
        }
        return List.of("X-NLP is a natural language processing evaluation framework.",
                "Model reranking scores documents by relevance to a query.");
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private boolean requiresApiKey(ModelConfig config) {
        return config.getProtocol() != ModelProtocol.OLLAMA_CHAT
                && config.getProtocol() != ModelProtocol.OLLAMA_EMBEDDINGS
                && config.getProtocol() != ModelProtocol.SPRING_AI_CHAT
                && config.getProtocol() != ModelProtocol.SPRING_AI_EMBEDDING
                && config.getProtocol() != ModelProtocol.LOCAL_JAVA_SPI
                && config.getProtocol() != ModelProtocol.LOCAL_CLASSIFIER;
    }

    private boolean isNlpComponent(ModelType type) {
        return type == ModelType.TOKENIZATION
                || type == ModelType.PART_OF_SPEECH
                || type == ModelType.NAMED_ENTITY_RECOGNITION
                || type == ModelType.DEPENDENCY_PARSING
                || type == ModelType.SEMANTIC_ROLE_LABELING
                || type == ModelType.TEXT_CLASSIFICATION;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
