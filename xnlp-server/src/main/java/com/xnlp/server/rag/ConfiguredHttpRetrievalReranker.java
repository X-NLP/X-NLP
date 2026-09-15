package com.xnlp.server.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.rag.RetrievalReranker;
import com.xnlp.core.repository.ModelConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cohere/Jina-compatible HTTP adapter backed by tenant-scoped model profiles. */
@Component
@Profile("!memory")
@Order(Ordered.LOWEST_PRECEDENCE)
public class ConfiguredHttpRetrievalReranker implements RetrievalReranker {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ModelConfigRepository modelConfigs;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public ConfiguredHttpRetrievalReranker(ModelConfigRepository modelConfigs, ObjectMapper mapper) {
        this(modelConfigs, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    ConfiguredHttpRetrievalReranker(
            ModelConfigRepository modelConfigs,
            ObjectMapper mapper,
            HttpClient http) {
        this.modelConfigs = modelConfigs;
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public String name() {
        return requireConfig().getName();
    }

    @Override
    public List<RetrievalMatch> rerank(String query, List<RetrievalMatch> candidates, int topN) {
        ModelConfig config = requireConfig();
        validateConfig(config);
        List<RetrievalMatch> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (safeCandidates.isEmpty()) {
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(config.getBaseUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "model", config.getModelName(),
                            "query", query,
                            "documents", safeCandidates.stream().map(RetrievalMatch::content).toList(),
                            "top_n", Math.min(topN, safeCandidates.size())))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Reranker provider returned HTTP " + response.statusCode());
            }
            return mapResults(safeCandidates, mapper.readValue(response.body(), MAP_TYPE), topN);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reranker provider request was interrupted");
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Reranker provider response was invalid");
        }
    }

    List<RetrievalMatch> mapResults(
            List<RetrievalMatch> candidates,
            Map<String, Object> body,
            int topN) {
        Object rawResults = body == null ? null : body.get("results");
        if (!(rawResults instanceof List<?> results)) {
            throw new IllegalArgumentException("Reranker response must contain results");
        }
        Set<Integer> seenIndexes = new LinkedHashSet<>();
        List<RetrievalMatch> reranked = new ArrayList<>();
        for (Object rawResult : results) {
            if (!(rawResult instanceof Map<?, ?> result)) {
                throw new IllegalArgumentException("Reranker result must be an object");
            }
            int index = integerValue(result.get("index"), "index");
            if (index < 0 || index >= candidates.size() || !seenIndexes.add(index)) {
                throw new IllegalArgumentException("Reranker result index is invalid or duplicated");
            }
            double score = scoreValue(result.get("relevance_score"));
            RetrievalMatch original = candidates.get(index);
            reranked.add(new RetrievalMatch(
                    original.documentId(), original.chunkId(), original.title(), original.content(),
                    original.sourceUri(), original.score(), score, original.metadata()));
        }
        return reranked.stream()
                .sorted(java.util.Comparator.comparingDouble(RetrievalMatch::effectiveScore).reversed()
                        .thenComparing(RetrievalMatch::documentId)
                        .thenComparing(RetrievalMatch::chunkId))
                .limit(topN)
                .toList();
    }

    private ModelConfig requireConfig() {
        return modelConfigs.findAll().stream()
                .filter(config -> config.getType() == ModelType.RERANKING)
                .filter(config -> config.getProtocol() == ModelProtocol.COHERE_RERANK
                        || config.getProtocol() == ModelProtocol.JINA_RERANK)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No reranking model profile is configured"));
    }

    private void validateConfig(ModelConfig config) {
        requireText(config.getName(), "name");
        requireText(config.getBaseUrl(), "baseUrl");
        requireText(config.getApiKey(), "apiKey");
        requireText(config.getModelName(), "modelName");
    }

    private static URI endpoint(String baseUrl) {
        String normalized = requireText(baseUrl, "baseUrl");
        normalized = normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        return URI.create(normalized.endsWith("/rerank") ? normalized : normalized + "/rerank");
    }

    private static int integerValue(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        double raw = number.doubleValue();
        int result = number.intValue();
        if (!Double.isFinite(raw) || raw != result) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return result;
    }

    private static double scoreValue(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("relevance_score must be numeric");
        }
        double score = number.doubleValue();
        if (!Double.isFinite(score) || score < -1 || score > 1) {
            throw new IllegalArgumentException("relevance_score must be between -1 and 1");
        }
        return score;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
