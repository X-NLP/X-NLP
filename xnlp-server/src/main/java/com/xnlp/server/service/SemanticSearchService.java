package com.xnlp.server.service;

import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import io.micrometer.observation.annotation.Observed;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral semantic similarity and dataset search backed by Spring AI's
 * {@link EmbeddingModel}. The model is optional so the built-in NLP workbench
 * remains usable when no embedding provider is configured.
 */
@Service
public class SemanticSearchService {

    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final DatasetService datasetService;

    public SemanticSearchService(ObjectProvider<EmbeddingModel> embeddingModels, DatasetService datasetService) {
        this.embeddingModels = embeddingModels;
        this.datasetService = datasetService;
    }

    public boolean isAvailable() {
        return embeddingModels.orderedStream().findFirst().isPresent();
    }

    @Observed(name = "xnlp.embedding.similarity", contextualName = "embedding-similarity")
    public Map<String, Object> similarity(String text, String textPair) {
        requireText(text, "text");
        requireText(textPair, "textPair");
        EmbeddingModel model = embeddingModel();
        float[] queryVector = model.embed(text.strip());
        float[] candidateVector = model.embed(textPair.strip());

        Map<String, Object> result = new LinkedHashMap<>();
        double score = cosineSimilarity(queryVector, candidateVector);
        result.put("score", score);
        result.put("dimensions", queryVector.length);
        result.put("provider", providerName(model));
        result.put("runtime", "spring-ai-embedding");
        result.put("result", Map.of("score", score, "dimensions", queryVector.length));
        return result;
    }

    /**
     * Embeds the query and all dataset inputs for an auditable, deterministic
     * top-k search. Results retain the original entry and expected output so
     * they can be used directly by evaluation and annotation workflows.
     */
    @Observed(name = "xnlp.embedding.dataset.search", contextualName = "embedding-dataset-search")
    public Map<String, Object> searchDataset(String datasetId, String query, int topK) {
        requireText(query, "query");
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }

        EvaluationDataset dataset = datasetService.get(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));
        List<EvaluationEntry> entries = dataset.getEntries().stream()
                .filter(entry -> entry.getInput() != null && !entry.getInput().isBlank())
                .toList();
        EmbeddingModel model = embeddingModel();
        float[] queryVector = model.embed(query.strip());
        List<float[]> entryVectors = model.embed(entries.stream().map(EvaluationEntry::getInput).toList());

        List<Map<String, Object>> results = java.util.stream.IntStream.range(0, entries.size())
                .mapToObj(index -> {
                    EvaluationEntry entry = entries.get(index);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rank", index + 1);
                    item.put("score", cosineSimilarity(queryVector, entryVectors.get(index)));
                    item.put("entry", entry);
                    return item;
                })
                .sorted(Comparator.comparingDouble(item -> -((Number) item.get("score")).doubleValue()))
                .limit(topK)
                .toList();

        // Re-number after sorting so rank always represents result order.
        for (int index = 0; index < results.size(); index++) {
            results.get(index).put("rank", index + 1);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("datasetId", datasetId);
        response.put("query", query.strip());
        response.put("topK", topK);
        response.put("dimensions", queryVector.length);
        response.put("provider", providerName(model));
        response.put("runtime", "spring-ai-embedding");
        response.put("results", results);
        return response;
    }

    private EmbeddingModel embeddingModel() {
        return embeddingModels.orderedStream().findFirst().orElseThrow(() ->
                new IllegalStateException("No Spring AI EmbeddingModel is configured. "
                        + "Set SPRING_AI_MODEL_EMBEDDING and the provider embedding model configuration."));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("Embedding vectors must be non-empty and have equal dimensions");
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0d;
        return Math.max(-1d, Math.min(1d, dot / Math.sqrt(leftNorm * rightNorm)));
    }

    private static String providerName(EmbeddingModel model) {
        String name = model.getClass().getSimpleName();
        return name.endsWith("EmbeddingModel")
                ? name.substring(0, name.length() - "EmbeddingModel".length()).toLowerCase()
                : name;
    }
}
