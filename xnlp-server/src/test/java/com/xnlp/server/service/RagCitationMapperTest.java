package com.xnlp.server.service;

import com.xnlp.core.rag.RetrievalMatch;
import com.xnlp.server.config.RagChatProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagCitationMapperTest {

    @Test
    void map_keepsOnlyCanonicalKnownSourcesAndDeduplicatesInReferenceOrder() {
        RagChatProperties properties = new RagChatProperties();
        properties.setCitationExcerptCharacters(32);
        RagCitationMapper mapper = new RagCitationMapper(properties);
        Map<String, RetrievalMatch> sources = new LinkedHashMap<>();
        sources.put("S1", match("doc-1", "chunk-1", "first source"));
        sources.put("S2", match("doc-2", "chunk-2", "second   source with a deliberately long excerpt"));

        var mapping = mapper.map("Use the second source [S2]. Unknown [S999]. Again [S2].", sources);

        assertThat(mapping.answer())
                .contains("[S2]")
                .doesNotContain("[S999]");
        assertThat(mapping.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isEqualTo("doc-2");
            assertThat(citation.chunkId()).isEqualTo("chunk-2");
            assertThat(citation.title()).isEqualTo("Title doc-2");
            assertThat(citation.excerpt()).hasSizeLessThanOrEqualTo(33).endsWith("…");
        });
    }

    private static RetrievalMatch match(String documentId, String chunkId, String content) {
        return new RetrievalMatch(
                documentId, chunkId, "Title " + documentId, content,
                "https://example.test/" + documentId, 0.8, null, Map.of());
    }
}
