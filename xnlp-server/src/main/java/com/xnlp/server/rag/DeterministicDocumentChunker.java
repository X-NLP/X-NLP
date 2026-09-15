package com.xnlp.server.rag;

import com.xnlp.core.rag.ChunkPolicy;
import com.xnlp.core.rag.KnowledgeChunk;
import com.xnlp.core.rag.KnowledgeDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Character-based chunker whose output is stable for the same source text and
 * policy. Chunks retain exact offsets into the original source; trimming only
 * adjusts the recorded range and never rewrites the text.
 */
@Component
public class DeterministicDocumentChunker implements DocumentChunker {

    @Override
    public List<KnowledgeChunk> chunk(KnowledgeDocument document, ChunkPolicy policy) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        ChunkPolicy effectivePolicy = policy == null ? ChunkPolicy.defaults() : policy;
        String source = document.content();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int cursor = 0;
        int sequence = 0;

        while (cursor < source.length()) {
            int hardEnd = Math.min(source.length(), cursor + effectivePolicy.maxCharacters());
            int rawEnd = chooseEnd(source, cursor, hardEnd, effectivePolicy.separatorMode());
            Range range = trimRange(source, cursor, rawEnd);
            if (!range.isEmpty()) {
                String content = source.substring(range.start(), range.end());
                String checksum = sha256(content);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("sequence", sequence);
                metadata.put("startOffset", range.start());
                metadata.put("endOffset", range.end());
                chunks.add(new KnowledgeChunk(
                        chunkId(document.id(), sequence, range.start(), range.end(), checksum),
                        document.knowledgeBaseId(),
                        document.id(),
                        sequence,
                        content,
                        checksum,
                        range.start(),
                        range.end(),
                        metadata));
                sequence++;
            }

            if (rawEnd >= source.length()) {
                break;
            }
            int next = rawEnd - effectivePolicy.overlapCharacters();
            cursor = Math.max(cursor + 1, next);
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("document content must contain non-whitespace text");
        }
        return List.copyOf(chunks);
    }

    private int chooseEnd(String source, int start, int hardEnd, ChunkPolicy.SeparatorMode mode) {
        if (hardEnd >= source.length() || mode == ChunkPolicy.SeparatorMode.FIXED) {
            return hardEnd;
        }
        int boundary = switch (mode) {
            case PARAGRAPH -> paragraphBoundary(source, start, hardEnd);
            case SENTENCE -> sentenceBoundary(source, start, hardEnd);
            case FIXED -> hardEnd;
        };
        return boundary > start ? boundary : hardEnd;
    }

    private int paragraphBoundary(String source, int start, int hardEnd) {
        int best = -1;
        for (int i = start; i < hardEnd - 1; i++) {
            if (source.charAt(i) == '\n' && source.charAt(i + 1) == '\n') {
                int end = i + 2;
                while (end < hardEnd && source.charAt(end) == '\n') {
                    end++;
                }
                best = end;
            }
        }
        return best;
    }

    private int sentenceBoundary(String source, int start, int hardEnd) {
        int best = -1;
        for (int i = start; i < hardEnd; i++) {
            char current = source.charAt(i);
            if (current == '.' || current == '!' || current == '?' || current == '。'
                    || current == '！' || current == '？') {
                int end = i + 1;
                while (end < hardEnd && Character.isWhitespace(source.charAt(end))) {
                    end++;
                }
                best = end;
            }
        }
        return best;
    }

    private Range trimRange(String source, int start, int end) {
        int adjustedStart = start;
        int adjustedEnd = end;
        while (adjustedStart < adjustedEnd && Character.isWhitespace(source.charAt(adjustedStart))) {
            adjustedStart++;
        }
        while (adjustedEnd > adjustedStart && Character.isWhitespace(source.charAt(adjustedEnd - 1))) {
            adjustedEnd--;
        }
        return new Range(adjustedStart, adjustedEnd);
    }

    private String chunkId(String documentId, int sequence, int start, int end, String checksum) {
        return sha256(documentId + "|" + sequence + "|" + start + "|" + end + "|" + checksum);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record Range(int start, int end) {
        boolean isEmpty() {
            return start >= end;
        }
    }
}
