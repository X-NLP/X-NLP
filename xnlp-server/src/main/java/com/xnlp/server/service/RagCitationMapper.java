package com.xnlp.server.service;

import com.xnlp.core.rag.Citation;
import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.server.config.RagChatProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps model source labels to canonical retrieval citations and removes unknown labels. */
@Component
@Profile("!memory")
public class RagCitationMapper {

    private static final Pattern SOURCE_LABEL = Pattern.compile("\\[S(\\d+)]");

    private final RagChatProperties properties;

    public RagCitationMapper(RagChatProperties properties) {
        this.properties = properties;
    }

    public CitationMapping map(String answer, Map<String, RetrievalMatch> sources) {
        String safeAnswer = requireText(answer, "answer");
        Map<String, RetrievalMatch> safeSources = sources == null ? Map.of() : sources;
        Matcher matcher = SOURCE_LABEL.matcher(safeAnswer);
        StringBuilder sanitized = new StringBuilder(safeAnswer.length());
        Set<String> referenced = new LinkedHashSet<>();
        while (matcher.find()) {
            String label = "S" + matcher.group(1);
            if (safeSources.containsKey(label)) {
                referenced.add(label);
                matcher.appendReplacement(sanitized, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(sanitized, "");
            }
        }
        matcher.appendTail(sanitized);

        List<Citation> citations = new ArrayList<>();
        for (String label : referenced) {
            RetrievalMatch match = safeSources.get(label);
            citations.add(new Citation(
                    match.documentId(), match.chunkId(), match.title(), match.sourceUri(), excerpt(match.content())));
        }
        return new CitationMapping(requireText(sanitized.toString(), "answer"), List.copyOf(citations));
    }

    private String excerpt(String content) {
        String normalized = content.replaceAll("\\s+", " ").strip();
        int max = properties.getCitationExcerptCharacters();
        if (normalized.length() <= max) {
            return normalized;
        }
        int end = max;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end).stripTrailing() + "…";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public record CitationMapping(String answer, List<Citation> citations) {
    }
}
