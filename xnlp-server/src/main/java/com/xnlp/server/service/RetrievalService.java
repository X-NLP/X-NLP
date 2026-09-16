package com.xnlp.server.service;

import com.xnlp.core.rag.KnowledgeBase;
import com.xnlp.core.rag.KnowledgeVectorStore;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.rag.RetrievalReranker;
import com.xnlp.core.rag.RetrievalResult;
import com.xnlp.core.rag.VectorSearchRequest;
import com.xnlp.core.repository.KnowledgeBaseRepository;
import com.xnlp.server.config.RetrievalProperties;
import com.xnlp.server.tenant.TenantContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Coordinates tenant-scoped query embedding, vector search and optional reranking. */
@Service
@Profile("!memory")
public class RetrievalService {

    private static final Comparator<RetrievalMatch> STABLE_SCORE_ORDER =
            Comparator.comparingDouble(RetrievalMatch::effectiveScore).reversed()
                    .thenComparing(RetrievalMatch::documentId)
                    .thenComparing(RetrievalMatch::chunkId);

    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final ObjectProvider<RetrievalReranker> rerankers;
    private final ObjectProvider<Tracer> tracers;
    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeVectorStore vectorStore;
    private final RetrievalProperties properties;
    private final MetricsService metrics;

    public RetrievalService(
            ObjectProvider<EmbeddingModel> embeddingModels,
            ObjectProvider<RetrievalReranker> rerankers,
            ObjectProvider<Tracer> tracers,
            KnowledgeBaseRepository knowledgeBases,
            KnowledgeVectorStore vectorStore,
            RetrievalProperties properties,
            MetricsService metrics) {
        this.embeddingModels = embeddingModels;
        this.rerankers = rerankers;
        this.tracers = tracers;
        this.knowledgeBases = knowledgeBases;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.metrics = metrics;
    }

    public RetrievalResult search(
            String knowledgeBaseId,
            String query,
            int topK,
            Double minScore,
            Map<String, Object> filter,
            boolean rerank,
            int rerankTopN) {
        String normalizedKnowledgeBaseId = requireText(knowledgeBaseId, "knowledgeBaseId");
        String normalizedQuery = requireText(query, "query");
        validateLimits(topK, rerankTopN);
        String tenantId = TenantContext.currentTenantId();
        long totalStarted = System.nanoTime();
        boolean success = false;
        try {
            KnowledgeBase knowledgeBase = knowledgeBases.findById(tenantId, normalizedKnowledgeBaseId)
                    .orElseThrow(() -> new RagContractException(
                            RagErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                            "Knowledge base was not found",
                            Map.of("knowledgeBaseId", normalizedKnowledgeBaseId)));

            float[] queryVector = timed(MetricsService.RetrievalPhase.EMBEDDING,
                    () -> embedQuery(normalizedQuery));
            List<RetrievalMatch> vectorMatches = timed(MetricsService.RetrievalPhase.VECTOR_SEARCH,
                    () -> vectorStore.search(new VectorSearchRequest(
                            tenantId, knowledgeBase.id(), knowledgeBase.embeddingModel(), queryVector,
                            topK, minScore, filter)));
            List<RetrievalMatch> stableMatches = stableOrder(vectorMatches, topK);

            String rerankerName = null;
            if (rerank && !stableMatches.isEmpty()) {
                RerankOutcome outcome = rerankWithPolicy(normalizedQuery, stableMatches, rerankTopN);
                stableMatches = outcome.matches();
                rerankerName = outcome.rerankerName();
            }

            success = true;
            return new RetrievalResult(
                    normalizedQuery,
                    stableMatches,
                    knowledgeBase.embeddingModel(),
                    rerankerName,
                    elapsedMillis(totalStarted),
                    currentTraceId());
        } finally {
            metrics.recordRetrievalPhase(
                    MetricsService.RetrievalPhase.TOTAL, success, System.nanoTime() - totalStarted);
        }
    }

    private float[] embedQuery(String query) {
        EmbeddingModel model = embeddingModels.orderedStream().findFirst()
                .orElseThrow(() -> new RagContractException(
                        RagErrorCode.PROVIDER_UNCONFIGURED,
                        "No Spring AI EmbeddingModel is configured"));
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                return requireVector(model.embed(query));
            } catch (RagContractException contractFailure) {
                throw contractFailure;
            } catch (RuntimeException providerFailure) {
                if (attempt >= properties.getMaxRetries()) {
                    throw new RagContractException(
                            RagErrorCode.PROVIDER_UNCONFIGURED,
                            "Embedding provider request failed");
                }
                sleepBeforeRetry();
            }
        }
        throw new RagContractException(
                RagErrorCode.PROVIDER_UNCONFIGURED,
                "Embedding provider request failed");
    }

    private RerankOutcome rerankWithPolicy(String query, List<RetrievalMatch> candidates, int topN) {
        try {
            return timed(MetricsService.RetrievalPhase.RERANK, () -> {
                RetrievalReranker reranker = rerankers.orderedStream().findFirst()
                        .orElseThrow(() -> new RagContractException(
                                RagErrorCode.RERANKER_UNAVAILABLE,
                                "No retrieval reranker is configured"));
                String rerankerName = requireText(reranker.name(), "reranker.name");
                List<RetrievalMatch> reranked = validateRerankerOutput(
                        candidates, reranker.rerank(query, candidates, topN), topN);
                return new RerankOutcome(reranked, rerankerName);
            });
        } catch (RuntimeException rerankFailure) {
            if (properties.getRerankFailurePolicy() == RetrievalProperties.RerankFailurePolicy.FALLBACK) {
                return new RerankOutcome(candidates, null);
            }
            throw new RagContractException(
                    RagErrorCode.RERANKER_UNAVAILABLE,
                    "Retrieval reranker request failed");
        }
    }

    private List<RetrievalMatch> validateRerankerOutput(
            List<RetrievalMatch> candidates,
            List<RetrievalMatch> reranked,
            int topN) {
        if (reranked == null) {
            throw new IllegalArgumentException("Reranker output must not be null");
        }
        Map<MatchKey, RetrievalMatch> originals = new LinkedHashMap<>();
        for (RetrievalMatch candidate : candidates) {
            MatchKey key = new MatchKey(candidate.documentId(), candidate.chunkId());
            if (originals.putIfAbsent(key, candidate) != null) {
                throw new IllegalArgumentException("Vector search returned duplicate candidates");
            }
        }

        Set<MatchKey> seen = new LinkedHashSet<>();
        List<RetrievalMatch> canonical = new ArrayList<>();
        for (RetrievalMatch item : reranked) {
            if (item == null) {
                throw new IllegalArgumentException("Reranker output must not contain null items");
            }
            MatchKey key = new MatchKey(item.documentId(), item.chunkId());
            RetrievalMatch original = originals.get(key);
            if (original == null || !seen.add(key)) {
                throw new IllegalArgumentException("Reranker output contains an unknown or duplicate candidate");
            }
            if (item.rerankScore() == null) {
                throw new IllegalArgumentException("Reranker output must provide rerankScore");
            }
            canonical.add(new RetrievalMatch(
                    original.documentId(), original.chunkId(), original.title(), original.content(),
                    original.sourceUri(), original.score(), item.rerankScore(), original.metadata()));
        }
        return canonical.stream().sorted(STABLE_SCORE_ORDER).limit(topN).toList();
    }

    private List<RetrievalMatch> stableOrder(List<RetrievalMatch> matches, int limit) {
        if (matches == null) {
            throw new IllegalArgumentException("Vector search output must not be null");
        }
        return matches.stream().sorted(STABLE_SCORE_ORDER).limit(limit).toList();
    }

    private <T> T timed(MetricsService.RetrievalPhase phase, Supplier<T> action) {
        long started = System.nanoTime();
        boolean success = false;
        try {
            T result = action.get();
            success = true;
            return result;
        } finally {
            metrics.recordRetrievalPhase(phase, success, System.nanoTime() - started);
        }
    }

    private void sleepBeforeRetry() {
        long millis = properties.getRetryBackoff().toMillis();
        if (millis == 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RagContractException(
                    RagErrorCode.PROVIDER_UNCONFIGURED,
                    "Embedding provider request was interrupted");
        }
    }

    private String currentTraceId() {
        Tracer tracer = tracers.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    private static float[] requireVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new RagContractException(
                    RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                    "Embedding provider returned an empty query vector");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new RagContractException(
                        RagErrorCode.VECTOR_DIMENSION_MISMATCH,
                        "Embedding provider returned a non-finite query vector");
            }
        }
        return vector.clone();
    }

    private static void validateLimits(int topK, int rerankTopN) {
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        if (rerankTopN < 1 || rerankTopN > topK) {
            throw new IllegalArgumentException("rerankTopN must be between 1 and topK");
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private record MatchKey(String documentId, String chunkId) {
    }

    private record RerankOutcome(List<RetrievalMatch> matches, String rerankerName) {
    }
}
