package com.xnlp.server.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.server.evaluation.recovery.EvaluationSampleResult;

import java.time.Instant;
import java.util.Map;

/** Persisted result for one sample within an evaluation run. */
public record EvaluationSampleResultResponse(
        String id,
        String runId,
        String sourceRunId,
        String datasetEntryId,
        int sequence,
        String status,
        String expectedOutput,
        String actualOutput,
        Map<String, Double> scores,
        String errorCode,
        String errorMessage,
        int attempt,
        Instant completedAt) {

    public EvaluationSampleResultResponse {
        scores = scores == null ? Map.of() : Map.copyOf(scores);
    }

    public static EvaluationSampleResultResponse from(
            EvaluationSampleResult result, String sourceRunId, int attempt, ObjectMapper mapper) {
        return new EvaluationSampleResultResponse(
                result.sampleId(), result.runId(), sourceRunId, result.sampleId(), result.sequence(),
                result.status().name().toLowerCase(), result.expectedOutput(), result.actualOutput(),
                scores(result.scoreJson(), mapper), result.errorCode(), result.errorMessage(), attempt,
                result.completedAt());
    }

    private static Map<String, Double> scores(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }
}
