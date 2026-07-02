package com.xnlp.core.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computed metrics for a single evaluation run.
 *
 * <p>Field set depends on the task type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluationMetrics {

    private int totalEntries;
    private int correctEntries;
    private double accuracy;

    private Double precisionMacro;
    private Double recallMacro;
    private Double f1Macro;
    private Map<String, Double> perClassF1 = new LinkedHashMap<>();

    private Double rouge1;
    private Double rouge2;
    private Double rougeL;

    private Double bleu;

    private Double exactMatch;
    private Double f1Score;

    private Double entityF1;
    private int truePositive;
    private int falsePositive;
    private int falseNegative;

    public int getTotalEntries() { return totalEntries; }
    public void setTotalEntries(int totalEntries) { this.totalEntries = totalEntries; }
    public int getCorrectEntries() { return correctEntries; }
    public void setCorrectEntries(int correctEntries) { this.correctEntries = correctEntries; }
    public double getAccuracy() { return accuracy; }
    public void setAccuracy(double accuracy) { this.accuracy = accuracy; }
    public Double getPrecisionMacro() { return precisionMacro; }
    public void setPrecisionMacro(Double precisionMacro) { this.precisionMacro = precisionMacro; }
    public Double getRecallMacro() { return recallMacro; }
    public void setRecallMacro(Double recallMacro) { this.recallMacro = recallMacro; }
    public Double getF1Macro() { return f1Macro; }
    public void setF1Macro(Double f1Macro) { this.f1Macro = f1Macro; }
    public Map<String, Double> getPerClassF1() { return perClassF1; }
    public void setPerClassF1(Map<String, Double> perClassF1) { this.perClassF1 = new LinkedHashMap<>(perClassF1); }
    public Double getRouge1() { return rouge1; }
    public void setRouge1(Double rouge1) { this.rouge1 = rouge1; }
    public Double getRouge2() { return rouge2; }
    public void setRouge2(Double rouge2) { this.rouge2 = rouge2; }
    public Double getRougeL() { return rougeL; }
    public void setRougeL(Double rougeL) { this.rougeL = rougeL; }
    public Double getBleu() { return bleu; }
    public void setBleu(Double bleu) { this.bleu = bleu; }
    public Double getExactMatch() { return exactMatch; }
    public void setExactMatch(Double exactMatch) { this.exactMatch = exactMatch; }
    public Double getF1Score() { return f1Score; }
    public void setF1Score(Double f1Score) { this.f1Score = f1Score; }
    public Double getEntityF1() { return entityF1; }
    public void setEntityF1(Double entityF1) { this.entityF1 = entityF1; }
    public int getTruePositive() { return truePositive; }
    public void setTruePositive(int truePositive) { this.truePositive = truePositive; }
    public int getFalsePositive() { return falsePositive; }
    public void setFalsePositive(int falsePositive) { this.falsePositive = falsePositive; }
    public int getFalseNegative() { return falseNegative; }
    public void setFalseNegative(int falseNegative) { this.falseNegative = falseNegative; }
}
