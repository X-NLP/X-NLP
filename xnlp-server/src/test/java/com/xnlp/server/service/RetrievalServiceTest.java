package com.xnlp.server.service;

import com.xnlp.core.rag.ChunkPolicy;
import com.xnlp.core.rag.KnowledgeBase;
import com.xnlp.core.rag.KnowledgeVectorStore;
import com.xnlp.core.rag.RagContractException;
import com.xnlp.core.rag.RagErrorCode;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.rag.RetrievalReranker;
import com.xnlp.core.rag.VectorSearchRequest;
import com.xnlp.core.repository.KnowledgeBaseRepository;
import com.xnlp.server.config.RetrievalProperties;
import com.xnlp.server.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void search_propagatesTenantAndFiltersAndUsesStableTieBreakOrder() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed("query")).thenReturn(new float[]{1, 0});
        KnowledgeBaseRepository knowledgeBases = repositoryWith(knowledgeBase());
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        when(vectorStore.search(any())).thenReturn(List.of(
                match("doc-b", "chunk-a", "second", 0.8, null),
                match("doc-a", "chunk-z", "first", 0.8, null),
                match("doc-c", "chunk-a", "third", 0.2, null)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalService service = service(
                providerOf(embeddings), providerOf(), knowledgeBases, vectorStore,
                new RetrievalProperties(), registry);
        TenantContext.setTenantId("tenant-a");

        var result = service.search(
                "kb-1", " query ", 2, 0.5, Map.of("language", "en"), false, 2);

        assertThat(result.query()).isEqualTo("query");
        assertThat(result.matches()).extracting(RetrievalMatch::documentId)
                .containsExactly("doc-a", "doc-b");
        assertThat(result.embeddingModel()).isEqualTo("embed-v1");
        assertThat(result.reranker()).isNull();
        ArgumentCaptor<VectorSearchRequest> request = ArgumentCaptor.forClass(VectorSearchRequest.class);
        verify(vectorStore).search(request.capture());
        assertThat(request.getValue().tenantId()).isEqualTo("tenant-a");
        assertThat(request.getValue().knowledgeBaseId()).isEqualTo("kb-1");
        assertThat(request.getValue().embeddingModel()).isEqualTo("embed-v1");
        assertThat(request.getValue().minScore()).isEqualTo(0.5);
        assertThat(request.getValue().filter()).containsEntry("language", "en");
        assertTimerCount(registry, "xnlp.rag.retrieval.embedding.duration", "success", 1);
        assertTimerCount(registry, "xnlp.rag.retrieval.vector.search.duration", "success", 1);
        assertTimerCount(registry, "xnlp.rag.retrieval.total.duration", "success", 1);
    }

    @Test
    void search_reranksCanonicalCandidatesAndIgnoresProviderContentMutation() {
        EmbeddingModel embeddings = embeddingModel();
        KnowledgeVectorStore vectorStore = vectorStoreWith(List.of(
                match("doc-a", "chunk-a", "trusted-a", 0.9, null),
                match("doc-b", "chunk-b", "trusted-b", 0.8, null)));
        RetrievalReranker reranker = mock(RetrievalReranker.class);
        when(reranker.name()).thenReturn("rerank-v1");
        when(reranker.rerank(any(), any(), any(Integer.class))).thenReturn(List.of(
                match("doc-b", "chunk-b", "tampered-b", 0.1, 0.95),
                match("doc-a", "chunk-a", "tampered-a", 0.1, 0.40)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalService service = service(
                providerOf(embeddings), providerOf(reranker), repositoryWith(knowledgeBase()),
                vectorStore, new RetrievalProperties(), registry);

        var result = service.search("kb-1", "query", 2, null, Map.of(), true, 2);

        assertThat(result.reranker()).isEqualTo("rerank-v1");
        assertThat(result.matches()).extracting(RetrievalMatch::documentId)
                .containsExactly("doc-b", "doc-a");
        assertThat(result.matches()).extracting(RetrievalMatch::content)
                .containsExactly("trusted-b", "trusted-a");
        assertThat(result.matches()).extracting(RetrievalMatch::score)
                .containsExactly(0.8, 0.9);
        assertTimerCount(registry, "xnlp.rag.retrieval.rerank.duration", "success", 1);
    }

    @Test
    void search_missingRerankerFallsBackWithoutChangingVectorResults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalService service = service(
                providerOf(embeddingModel()), providerOf(), repositoryWith(knowledgeBase()),
                vectorStoreWith(List.of(match("doc-a", "chunk-a", "trusted", 0.9, null))),
                new RetrievalProperties(), registry);

        var result = service.search("kb-1", "query", 1, null, Map.of(), true, 1);

        assertThat(result.reranker()).isNull();
        assertThat(result.matches()).singleElement().satisfies(match -> {
            assertThat(match.documentId()).isEqualTo("doc-a");
            assertThat(match.rerankScore()).isNull();
        });
        assertTimerCount(registry, "xnlp.rag.retrieval.rerank.duration", "error", 1);
        assertTimerCount(registry, "xnlp.rag.retrieval.total.duration", "success", 1);
    }

    @Test
    void search_missingRerankerFailsStrictlyWithSanitizedContractError() {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setRerankFailurePolicy(RetrievalProperties.RerankFailurePolicy.FAIL);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalService service = service(
                providerOf(embeddingModel()), providerOf(), repositoryWith(knowledgeBase()),
                vectorStoreWith(List.of(match("doc-a", "chunk-a", "trusted", 0.9, null))),
                properties, registry);

        assertThatThrownBy(() -> service.search("kb-1", "query", 1, null, Map.of(), true, 1))
                .isInstanceOfSatisfying(RagContractException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.RERANKER_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("Retrieval reranker request failed");
                });
        assertTimerCount(registry, "xnlp.rag.retrieval.rerank.duration", "error", 1);
        assertTimerCount(registry, "xnlp.rag.retrieval.total.duration", "error", 1);
    }

    @Test
    void search_embeddingProviderFailureDoesNotExposeProviderSecrets() {
        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed("query")).thenThrow(new IllegalStateException(
                "Authorization: Bearer super-secret database.password=hunter2"));
        RetrievalProperties properties = new RetrievalProperties();
        properties.setMaxRetries(0);
        RetrievalService service = service(
                providerOf(embeddings), providerOf(), repositoryWith(knowledgeBase()),
                mock(KnowledgeVectorStore.class), properties, new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.search("kb-1", "query", 1, null, Map.of(), false, 1))
                .isInstanceOfSatisfying(RagContractException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(RagErrorCode.PROVIDER_UNCONFIGURED);
                    assertThat(error.getMessage()).isEqualTo("Embedding provider request failed");
                    assertThat(error.toString()).doesNotContain(
                            "super-secret", "hunter2", "Authorization", "database.password");
                });
    }

    @Test
    void search_invalidRerankerCandidatesUseConfiguredFallbackOrStrictFailure() {
        RetrievalReranker invalid = mock(RetrievalReranker.class);
        when(invalid.name()).thenReturn("untrusted-reranker");
        when(invalid.rerank(any(), any(), any(Integer.class))).thenReturn(List.of(
                match("unknown", "chunk-x", "injected", 0.8, 0.9)));
        RetrievalProperties fallback = new RetrievalProperties();
        RetrievalService fallbackService = service(
                providerOf(embeddingModel()), providerOf(invalid), repositoryWith(knowledgeBase()),
                vectorStoreWith(List.of(match("doc-a", "chunk-a", "trusted", 0.9, null))),
                fallback, new SimpleMeterRegistry());

        var result = fallbackService.search("kb-1", "query", 1, null, Map.of(), true, 1);
        assertThat(result.matches()).singleElement()
                .extracting(RetrievalMatch::documentId).isEqualTo("doc-a");

        RetrievalProperties strict = new RetrievalProperties();
        strict.setRerankFailurePolicy(RetrievalProperties.RerankFailurePolicy.FAIL);
        RetrievalService strictService = service(
                providerOf(embeddingModel()), providerOf(invalid), repositoryWith(knowledgeBase()),
                vectorStoreWith(List.of(match("doc-a", "chunk-a", "trusted", 0.9, null))),
                strict, new SimpleMeterRegistry());
        assertThatThrownBy(() -> strictService.search(
                "kb-1", "query", 1, null, Map.of(), true, 1))
                .isInstanceOfSatisfying(RagContractException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(RagErrorCode.RERANKER_UNAVAILABLE));
    }

    private RetrievalService service(
            ObjectProvider<EmbeddingModel> embeddings,
            ObjectProvider<RetrievalReranker> rerankers,
            KnowledgeBaseRepository knowledgeBases,
            KnowledgeVectorStore vectorStore,
            RetrievalProperties properties,
            SimpleMeterRegistry registry) {
        return new RetrievalService(
                embeddings, rerankers, emptyProvider(), knowledgeBases, vectorStore,
                properties, new MetricsService(registry));
    }

    private static EmbeddingModel embeddingModel() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query")).thenReturn(new float[]{1, 0});
        return model;
    }

    private static KnowledgeVectorStore vectorStoreWith(List<RetrievalMatch> matches) {
        KnowledgeVectorStore store = mock(KnowledgeVectorStore.class);
        when(store.search(any())).thenReturn(matches);
        return store;
    }

    private static KnowledgeBaseRepository repositoryWith(KnowledgeBase knowledgeBase) {
        KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
        when(repository.findById(any(), any())).thenReturn(Optional.of(knowledgeBase));
        return repository;
    }

    private static KnowledgeBase knowledgeBase() {
        Instant now = Instant.now();
        return new KnowledgeBase(
                "kb-1", "Handbook", null, "embed-v1", ChunkPolicy.defaults(),
                KnowledgeBase.Status.ACTIVE, 2, 2, now, now);
    }

    private static RetrievalMatch match(
            String documentId,
            String chunkId,
            String content,
            double score,
            Double rerankScore) {
        return new RetrievalMatch(
                documentId, chunkId, "Title " + documentId, content, null,
                score, rerankScore, Map.of("language", "en"));
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T... values) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(ignored -> Stream.of(values));
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> emptyProvider() {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static void assertTimerCount(
            SimpleMeterRegistry registry,
            String name,
            String status,
            long count) {
        assertThat(registry.get(name).tag("status", status).timer().count()).isEqualTo(count);
    }
}
