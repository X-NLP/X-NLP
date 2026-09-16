package com.xnlp.core.rag.eval;

import com.xnlp.core.rag.RetrievalMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RetrievalEvaluationCalculatorTest {

    @Test
    void evaluate_calculatesRecallMrrAndBinaryNdcg() {
        var metrics = RetrievalEvaluationCalculator.evaluate(
                List.of("chunk-b", "chunk-c"),
                List.of(match("chunk-a"), match("chunk-b"), match("chunk-c")),
                List.of(match("chunk-a"), match("chunk-b"), match("chunk-c")),
                3);

        double expectedNdcg = (1 / log2(3) + 1 / log2(4)) / (1 + 1 / log2(3));
        assertThat(metrics.recallAtK()).isEqualTo(1);
        assertThat(metrics.reciprocalRank()).isEqualTo(0.5);
        assertThat(metrics.ndcgAtK()).isCloseTo(expectedNdcg, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(metrics.miss()).isFalse();
        assertThat(metrics.rerankChanged()).isFalse();
    }

    @Test
    void evaluate_marksMissAndRerankOrderChanges() {
        var miss = RetrievalEvaluationCalculator.evaluate(
                List.of("missing"), List.of(match("chunk-a")), List.of(match("chunk-b")), 1);

        assertThat(miss.recallAtK()).isZero();
        assertThat(miss.reciprocalRank()).isZero();
        assertThat(miss.ndcgAtK()).isZero();
        assertThat(miss.miss()).isTrue();
        assertThat(miss.rerankChanged()).isTrue();
    }

    @Test
    void evaluate_usesRequestedCutoffForIdealDcgWhenResultsAreShort() {
        var metrics = RetrievalEvaluationCalculator.evaluate(
                List.of("chunk-a", "chunk-b"),
                List.of(match("chunk-a")),
                List.of(match("chunk-a")),
                2);

        assertThat(metrics.recallAtK()).isEqualTo(0.5);
        assertThat(metrics.ndcgAtK()).isCloseTo(
                1 / (1 + 1 / log2(3)), org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    void aggregate_calculatesMacroMetricsAverageAndP95Latency() {
        var first = result("one", 1, 1, 1, 10);
        var second = result("two", 0, 0, 0, 30);

        RetrievalEvaluationMetrics metrics = RetrievalEvaluationCalculator.aggregate(List.of(first, second));

        assertThat(metrics.recallAtK()).isEqualTo(0.5);
        assertThat(metrics.mrr()).isEqualTo(0.5);
        assertThat(metrics.ndcgAtK()).isEqualTo(0.5);
        assertThat(metrics.averageLatencyMs()).isEqualTo(20);
        assertThat(metrics.p95LatencyMs()).isEqualTo(30);
    }

    @Test
    void evaluate_rejectsEmptyOrBlankRelevantLabels() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetrievalEvaluationCalculator.evaluate(List.of(), List.of(), List.of(), 1))
                .withMessageContaining("must not be empty");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetrievalEvaluationCalculator.evaluate(List.of(" "), List.of(), List.of(), 1))
                .withMessageContaining("blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RetrievalEvaluationCalculator.evaluate(
                        List.of("chunk-a"), List.of(), List.of(), 0))
                .withMessageContaining("topK");
    }

    private static RetrievalEvaluationSampleResult result(
            String id, double recall, double reciprocalRank, double ndcg, long latency) {
        return new RetrievalEvaluationSampleResult(
                id, "run-1", id, "query", List.of("chunk-a"), List.of(), List.of(),
                recall, reciprocalRank, ndcg, latency, recall == 0, false, null);
    }

    private static RetrievalMatch match(String chunkId) {
        return new RetrievalMatch(
                "doc-" + chunkId, chunkId, "Title", "Content", null,
                0.8, null, Map.of());
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
