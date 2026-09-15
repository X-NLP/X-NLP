package com.xnlp.core.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RAG answer with validated citations and provider diagnostics. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagAnswer(
        String answer,
        List<Citation> citations,
        RetrievalResult retrieval,
        String model,
        String provider,
        Map<String, Object> usage,
        long elapsedMs,
        String traceId) {

    public RagAnswer {
        answer = requireText(answer, "answer");
        citations = citations == null ? List.of() : List.copyOf(citations);
        model = requireText(model, "model");
        provider = requireText(provider, "provider");
        usage = usage == null || usage.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(usage));
        if (elapsedMs < 0) {
            throw new IllegalArgumentException("elapsedMs must not be negative");
        }
        if (retrieval != null) {
            var sources = retrieval.matches().stream()
                    .map(match -> new CitationSource(match.documentId(), match.chunkId()))
                    .collect(java.util.stream.Collectors.toSet());
            if (citations.stream().map(citation -> new CitationSource(citation.documentId(), citation.chunkId()))
                    .anyMatch(source -> !sources.contains(source))) {
                throw new IllegalArgumentException("citations must reference chunks in the retrieval result");
            }
        } else if (!citations.isEmpty()) {
            throw new IllegalArgumentException("citations require a retrieval result");
        }
    }

    private record CitationSource(String documentId, String chunkId) {
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
