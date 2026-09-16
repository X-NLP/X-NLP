package com.xnlp.core.rag.eval;

import com.xnlp.core.rag.RetrievalMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic binary-relevance metrics for retrieval evaluation. */
public final class RetrievalEvaluationCalculator {

    private RetrievalEvaluationCalculator() {
    }

    public static SampleMetrics evaluate(
            List<String> relevantChunkIds,
            List<RetrievalMatch> rawMatches,
            List<RetrievalMatch> finalMatches,
            int topK) {
        Set<String> relevant = normalizedRelevantIds(relevantChunkIds);
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        List<RetrievalMatch> raw = rawMatches == null ? List.of() : List.copyOf(rawMatches);
        List<RetrievalMatch> ranked = finalMatches == null ? List.of() : List.copyOf(finalMatches);
        int evaluatedResults = Math.min(topK, ranked.size());

        int hits = 0;
        int firstRelevantRank = 0;
        double dcg = 0;
        for (int index = 0; index < evaluatedResults; index++) {
            if (relevant.contains(ranked.get(index).chunkId())) {
                hits++;
                if (firstRelevantRank == 0) {
                    firstRelevantRank = index + 1;
                }
                dcg += 1.0 / log2(index + 2.0);
            }
        }
        double idealDcg = 0;
        int idealHits = Math.min(relevant.size(), topK);
        for (int index = 0; index < idealHits; index++) {
            idealDcg += 1.0 / log2(index + 2.0);
        }
        double recall = (double) hits / relevant.size();
        double reciprocalRank = firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank;
        double ndcg = idealDcg == 0 ? 0 : dcg / idealDcg;
        boolean changed = !raw.stream().map(RetrievalMatch::chunkId).toList()
                .equals(ranked.stream().map(RetrievalMatch::chunkId).toList());
        return new SampleMetrics(recall, reciprocalRank, ndcg, hits == 0, changed);
    }

    public static RetrievalEvaluationMetrics aggregate(List<RetrievalEvaluationSampleResult> samples) {
        if (samples == null || samples.isEmpty()) {
            return new RetrievalEvaluationMetrics(0, 0, 0, 0, 0);
        }
        double recall = samples.stream().mapToDouble(RetrievalEvaluationSampleResult::recallAtK).average().orElse(0);
        double mrr = samples.stream().mapToDouble(RetrievalEvaluationSampleResult::reciprocalRank).average().orElse(0);
        double ndcg = samples.stream().mapToDouble(RetrievalEvaluationSampleResult::ndcgAtK).average().orElse(0);
        double averageLatency = samples.stream().mapToLong(RetrievalEvaluationSampleResult::latencyMs).average().orElse(0);
        List<Long> latencies = new ArrayList<>(samples.stream()
                .map(RetrievalEvaluationSampleResult::latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList());
        int p95Index = Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1);
        return new RetrievalEvaluationMetrics(recall, mrr, ndcg, averageLatency, latencies.get(p95Index));
    }

    private static Set<String> normalizedRelevantIds(List<String> relevantChunkIds) {
        if (relevantChunkIds == null || relevantChunkIds.isEmpty()) {
            throw new IllegalArgumentException("relevantChunkIds must not be empty");
        }
        Set<String> result = new LinkedHashSet<>();
        for (String id : relevantChunkIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("relevantChunkIds must not contain blank values");
            }
            result.add(id.strip());
        }
        return result;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    public record SampleMetrics(
            double recallAtK,
            double reciprocalRank,
            double ndcgAtK,
            boolean miss,
            boolean rerankChanged) {
    }
}
