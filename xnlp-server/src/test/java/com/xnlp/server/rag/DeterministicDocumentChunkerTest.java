package com.xnlp.server.rag;

import com.xnlp.core.rag.ChunkPolicy;
import com.xnlp.core.rag.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicDocumentChunkerTest {

    private final DeterministicDocumentChunker chunker = new DeterministicDocumentChunker();

    @Test
    void chunk_sameSourceAndPolicy_producesStableIdsAndExactOffsets() {
        String content = "  First paragraph has enough detail for indexing.\n\n"
                + "Second paragraph is also deterministic and keeps its source position.  ";
        KnowledgeDocument document = document(content);
        ChunkPolicy policy = new ChunkPolicy(100, 20, ChunkPolicy.SeparatorMode.PARAGRAPH);

        var first = chunker.chunk(document, policy);
        var second = chunker.chunk(document, policy);

        assertThat(first).isEqualTo(second);
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.content()).isEqualTo(content.substring(chunk.startOffset(), chunk.endOffset()));
            assertThat(chunk.content().length()).isLessThanOrEqualTo(policy.maxCharacters());
            assertThat(chunk.id()).hasSize(64);
            assertThat(chunk.metadata()).containsEntry("startOffset", chunk.startOffset())
                    .containsEntry("endOffset", chunk.endOffset())
                    .containsEntry("sequence", chunk.sequence());
        });
    }

    @Test
    void chunk_fixedMode_honorsMaximumAndOverlapWithoutLosingProgress() {
        String content = "0123456789".repeat(35);
        ChunkPolicy policy = new ChunkPolicy(100, 25, ChunkPolicy.SeparatorMode.FIXED);

        var chunks = chunker.chunk(document(content), policy);

        assertThat(chunks).hasSize(5);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(100));
        assertThat(chunks.get(1).startOffset()).isEqualTo(chunks.get(0).endOffset() - 25);
        assertThat(chunks.getLast().endOffset()).isEqualTo(content.length());
    }

    @Test
    void chunk_sentenceMode_prefersSentenceBoundariesAndPreservesUnicodeOffsets() {
        String content = "第一句包含中文标点。第二句继续说明！Third sentence finishes here. "
                + "A final sentence makes the input longer than one hundred characters for validation.";
        ChunkPolicy policy = new ChunkPolicy(100, 10, ChunkPolicy.SeparatorMode.SENTENCE);

        var chunks = chunker.chunk(document(content), policy);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().content()).endsWith("here.");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content()).isEqualTo(content.substring(chunk.startOffset(), chunk.endOffset())));
    }

    private KnowledgeDocument document(String content) {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        return new KnowledgeDocument(
                "doc-1", "kb-1", "external-1", "Guide", KnowledgeDocument.SourceType.TEXT,
                null, content, DeterministicDocumentChunker.sha256(content), 1,
                KnowledgeDocument.IndexStatus.PENDING, null, Map.of(), now, now);
    }
}
