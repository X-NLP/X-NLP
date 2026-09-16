package com.xnlp.server.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.config.ModelProtocol;
import com.xnlp.core.config.ModelType;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.core.repository.ModelConfigRepository;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfiguredHttpRetrievalRerankerTest {

    @Test
    @SuppressWarnings("unchecked")
    void rerank_sendsCompatibleRequestAndMapsProviderIndexes() throws Exception {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        when(repository.findAll()).thenReturn(List.of(config()));
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"results":[
                  {"index":1,"relevance_score":0.91},
                  {"index":0,"relevance_score":0.42}
                ]}
                """);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var reranker = new ConfiguredHttpRetrievalReranker(repository, new ObjectMapper(), http);

        var result = reranker.rerank("what is X-NLP?", List.of(
                match("doc-a", "chunk-a", "first"),
                match("doc-b", "chunk-b", "second")), 2);

        assertThat(reranker.name()).isEqualTo("cohere-rerank");
        assertThat(result).extracting(RetrievalMatch::documentId).containsExactly("doc-b", "doc-a");
        assertThat(result).extracting(RetrievalMatch::rerankScore).containsExactly(0.91, 0.42);
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = requestCaptor.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://rerank.example/v1/rerank");
        assertThat(request.headers().firstValue("Authorization"))
                .contains("Bearer provider-secret");
    }

    @Test
    void mapResults_rejectsDuplicateUnknownAndNonFiniteProviderValues() {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        var reranker = new ConfiguredHttpRetrievalReranker(
                repository, new ObjectMapper(), mock(HttpClient.class));
        List<RetrievalMatch> candidates = List.of(match("doc-a", "chunk-a", "first"));

        assertThatThrownBy(() -> reranker.mapResults(candidates, Map.of(
                "results", List.of(Map.of("index", 1, "relevance_score", 0.9))), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reranker.mapResults(candidates, Map.of(
                "results", List.of(
                        Map.of("index", 0, "relevance_score", 0.9),
                        Map.of("index", 0, "relevance_score", 0.8))), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reranker.mapResults(candidates, Map.of(
                "results", List.of(Map.of("index", 0, "relevance_score", Double.NaN))), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ModelConfig config() {
        ModelConfig config = new ModelConfig();
        config.setName("cohere-rerank");
        config.setType(ModelType.RERANKING);
        config.setProtocol(ModelProtocol.COHERE_RERANK);
        config.setProvider("cohere");
        config.setModelName("rerank-v3.5");
        config.setBaseUrl("https://rerank.example/v1");
        config.setApiKey("provider-secret");
        return config;
    }

    private static RetrievalMatch match(String documentId, String chunkId, String content) {
        return new RetrievalMatch(
                documentId, chunkId, "Title", content, null, 0.5, null, Map.of());
    }
}
