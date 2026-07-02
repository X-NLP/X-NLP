package com.xnlp.server.service;

import com.xnlp.core.eval.EvaluationMetrics;
import com.xnlp.core.eval.NLPTaskType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Computes NLP evaluation metrics from pairs of (expected, actual) strings.
 */
@Component
public class MetricsCalculator {

    /**
     * Compute metrics for a list of (expected, actual) pairs given the task type.
     */
    public EvaluationMetrics compute(NLPTaskType taskType,
                                     List<Map.Entry<String, String>> predictions) {
        return switch (taskType) {
            case TEXT_CLASSIFICATION, SENTIMENT_ANALYSIS ->
                    computeClassification(predictions);
            case SUMMARIZATION -> computeRouge(predictions);
            case TRANSLATION -> computeBleu(predictions);
            case QUESTION_ANSWERING -> computeQA(predictions);
            case NAMED_ENTITY_RECOGNITION -> computeNER(predictions);
        };
    }

    private EvaluationMetrics computeClassification(List<Map.Entry<String, String>> preds) {
        EvaluationMetrics m = new EvaluationMetrics();
        m.setTotalEntries(preds.size());

        Map<String, int[]> classCounts = new LinkedHashMap<>(); // [tp, fp, fn]
        int correct = 0;

        for (var p : preds) {
            String expected = normalizeLabel(p.getKey());
            String actual = normalizeLabel(p.getValue());

            classCounts.putIfAbsent(expected, new int[]{0, 0, 0});
            classCounts.putIfAbsent(actual, new int[]{0, 0, 0});

            if (expected.equals(actual)) {
                correct++;
                classCounts.get(expected)[0]++; // TP
            } else {
                classCounts.get(expected)[2]++; // FN
                classCounts.get(actual)[1]++;   // FP
            }
        }

        m.setCorrectEntries(correct);
        m.setAccuracy(preds.isEmpty() ? 0 : (double) correct / preds.size());

        double totalP = 0, totalR = 0;
        int activeClasses = 0;
        for (var entry : classCounts.entrySet()) {
            int[] counts = entry.getValue();
            int tp = counts[0], fp = counts[1], fn = counts[2];
            double prec = (tp + fp) == 0 ? 0 : (double) tp / (tp + fp);
            double rec = (tp + fn) == 0 ? 0 : (double) tp / (tp + fn);
            double f1 = (prec + rec) == 0 ? 0 : 2 * prec * rec / (prec + rec);
            m.getPerClassF1().put(entry.getKey(), f1);
            totalP += prec;
            totalR += rec;
            if ((tp + fp + fn) > 0) activeClasses++;
        }

        if (activeClasses > 0) {
            m.setPrecisionMacro(totalP / activeClasses);
            m.setRecallMacro(totalR / activeClasses);
            double precM = m.getPrecisionMacro();
            double recM = m.getRecallMacro();
            m.setF1Macro((precM + recM) == 0 ? 0 : 2 * precM * recM / (precM + recM));
        }

        return m;
    }

    private EvaluationMetrics computeRouge(List<Map.Entry<String, String>> preds) {
        EvaluationMetrics m = new EvaluationMetrics();
        m.setTotalEntries(preds.size());

        double sumR1 = 0, sumR2 = 0, sumRL = 0;
        for (var p : preds) {
            List<String> ref = tokenize(p.getKey());
            List<String> hyp = tokenize(p.getValue());

            Set<String> refUnigrams = new HashSet<>(ref);
            Set<String> hypUnigrams = new HashSet<>(hyp);
            Set<String> overlap1 = new HashSet<>(refUnigrams);
            overlap1.retainAll(hypUnigrams);
            sumR1 += refUnigrams.isEmpty() ? 0 : (double) overlap1.size() / refUnigrams.size();

            Set<String> refBigrams = ngrams(ref, 2);
            Set<String> hypBigrams = ngrams(hyp, 2);
            Set<String> overlap2 = new HashSet<>(refBigrams);
            overlap2.retainAll(hypBigrams);
            sumR2 += refBigrams.isEmpty() ? 0 : (double) overlap2.size() / refBigrams.size();

            int lcsLen = lcs(ref, hyp);
            sumRL += ref.isEmpty() ? 0 : (double) lcsLen / ref.size();
        }

        int n = preds.size();
        m.setRouge1(n > 0 ? sumR1 / n : 0);
        m.setRouge2(n > 0 ? sumR2 / n : 0);
        m.setRougeL(n > 0 ? sumRL / n : 0);
        m.setAccuracy(m.getRouge1() != null ? m.getRouge1() : 0);
        return m;
    }

    private EvaluationMetrics computeBleu(List<Map.Entry<String, String>> preds) {
        EvaluationMetrics m = new EvaluationMetrics();
        m.setTotalEntries(preds.size());

        double sumBleu = 0;
        for (var p : preds) {
            List<String> ref = tokenize(p.getKey());
            List<String> hyp = tokenize(p.getValue());

            Set<String> refWords = new HashSet<>(ref);
            int matches = 0;
            for (String w : hyp) {
                if (refWords.contains(w)) matches++;
            }
            double prec = hyp.isEmpty() ? 0 : (double) matches / hyp.size();
            double bp = hyp.size() < ref.size()
                    ? Math.exp(1 - (double) ref.size() / Math.max(hyp.size(), 1))
                    : 1.0;
            sumBleu += bp * prec;
        }
        m.setBleu(preds.isEmpty() ? 0 : sumBleu / preds.size());
        m.setAccuracy(m.getBleu());
        return m;
    }

    private EvaluationMetrics computeQA(List<Map.Entry<String, String>> preds) {
        EvaluationMetrics m = new EvaluationMetrics();
        m.setTotalEntries(preds.size());

        int exactMatch = 0;
        double sumF1 = 0;
        for (var p : preds) {
            String expected = normalizeLabel(p.getKey());
            String actual = normalizeLabel(p.getValue());
            if (expected.equals(actual)) exactMatch++;

            Set<String> eTokens = new HashSet<>(tokenize(expected));
            Set<String> aTokens = new HashSet<>(tokenize(actual));
            Set<String> common = new HashSet<>(eTokens);
            common.retainAll(aTokens);
            double prec = aTokens.isEmpty() ? 0 : (double) common.size() / aTokens.size();
            double rec = eTokens.isEmpty() ? 0 : (double) common.size() / eTokens.size();
            sumF1 += (prec + rec) == 0 ? 0 : 2 * prec * rec / (prec + rec);
        }

        int n = preds.size();
        m.setExactMatch(n > 0 ? (double) exactMatch / n : 0);
        m.setF1Score(n > 0 ? sumF1 / n : 0);
        m.setAccuracy(m.getExactMatch());
        return m;
    }

    private EvaluationMetrics computeNER(List<Map.Entry<String, String>> preds) {
        // Simplified: treat each line as entity; strip whitespace; compare sets
        int tp = 0, fp = 0, fn = 0;
        for (var p : preds) {
            Set<String> expected = new HashSet<>(Arrays.asList(p.getKey().split("\\n")));
            Set<String> actual = new HashSet<>(Arrays.asList(p.getValue().split("\\n")));
            expected.removeIf(String::isBlank);
            actual.removeIf(String::isBlank);

            Set<String> common = new HashSet<>(expected);
            common.retainAll(actual);
            tp += common.size();
            fp += actual.size() - common.size();
            fn += expected.size() - common.size();
        }

        EvaluationMetrics m = new EvaluationMetrics();
        m.setTotalEntries(preds.size());
        m.setTruePositive(tp);
        m.setFalsePositive(fp);
        m.setFalseNegative(fn);
        double prec = (tp + fp) == 0 ? 0 : (double) tp / (tp + fp);
        double rec = (tp + fn) == 0 ? 0 : (double) tp / (tp + fn);
        m.setEntityF1((prec + rec) == 0 ? 0 : 2 * prec * rec / (prec + rec));
        m.setAccuracy(m.getEntityF1());
        return m;
    }

    // ---- helpers ----

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.asList(text.toLowerCase().split("\\s+"));
    }

    static Set<String> ngrams(List<String> tokens, int n) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= tokens.size() - n; i++) {
            result.add(String.join(" ", tokens.subList(i, i + n)));
        }
        return result;
    }

    static int lcs(List<String> a, List<String> b) {
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++)
            for (int j = 1; j <= b.size(); j++)
                dp[i][j] = a.get(i - 1).equals(b.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
        return dp[a.size()][b.size()];
    }

    static String normalizeLabel(String s) {
        if (s == null) return "";
        return s.strip().toLowerCase().replaceAll("\\p{Punct}", "").trim();
    }
}
