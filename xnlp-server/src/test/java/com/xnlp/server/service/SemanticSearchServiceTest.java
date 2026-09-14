package com.xnlp.server.service;

import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticSearchServiceTest {

    @Test
    void cosineSimilarity_returnsOneForIdenticalVectors() {
        assertThat(SemanticSearchService.cosineSimilarity(new float[]{1, 2}, new float[]{1, 2}))
                .isEqualTo(1.0d);
    }

    @Test
    void searchDataset_ordersEntriesByDescendingSimilarity() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query")).thenReturn(new float[]{1, 0});
        when(model.embed(List.of("closest", "farther")))
                .thenReturn(List.of(new float[]{1, 0}, new float[]{0, 1}));
        ObjectProvider<EmbeddingModel> models = providerOf(model);

        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setId("dataset-1");
        dataset.setEntries(List.of(
                new EvaluationEntry("entry-1", "closest", "yes"),
                new EvaluationEntry("entry-2", "farther", "no")));
        DatasetService datasets = mock(DatasetService.class);
        when(datasets.get("dataset-1")).thenReturn(Optional.of(dataset));

        Map<String, Object> response = new SemanticSearchService(models, datasets)
                .searchDataset("dataset-1", "query", 2);

        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        assertThat(results).hasSize(2);
        assertThat(((EvaluationEntry) results.getFirst().get("entry")).getInput()).isEqualTo("closest");
        assertThat(results.getFirst()).containsEntry("rank", 1);
        assertThat((double) results.getFirst().get("score")).isEqualTo(1.0d);
        assertThat((double) results.getLast().get("score")).isEqualTo(0.0d);
    }

    @Test
    void searchDataset_rejectsInvalidTopK() {
        DatasetService datasets = mock(DatasetService.class);
        SemanticSearchService service = new SemanticSearchService(providerOf(mock(EmbeddingModel.class)), datasets);

        assertThatThrownBy(() -> service.searchDataset("dataset-1", "query", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("topK must be between 1 and 100");
    }

    private static ObjectProvider<EmbeddingModel> providerOf(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(model));
        return provider;
    }
}
