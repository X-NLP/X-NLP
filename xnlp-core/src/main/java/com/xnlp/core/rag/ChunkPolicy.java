package com.xnlp.core.rag;

import java.util.Objects;

/** Deterministic document chunking policy persisted with a knowledge base. */
public record ChunkPolicy(
        int maxCharacters,
        int overlapCharacters,
        SeparatorMode separatorMode) {

    public static final int DEFAULT_MAX_CHARACTERS = 1_200;
    public static final int DEFAULT_OVERLAP_CHARACTERS = 200;

    public ChunkPolicy {
        if (maxCharacters < 100 || maxCharacters > 100_000) {
            throw new IllegalArgumentException("maxCharacters must be between 100 and 100000");
        }
        if (overlapCharacters < 0 || overlapCharacters >= maxCharacters) {
            throw new IllegalArgumentException("overlapCharacters must be non-negative and less than maxCharacters");
        }
        separatorMode = Objects.requireNonNull(separatorMode, "separatorMode must not be null");
    }

    public static ChunkPolicy defaults() {
        return new ChunkPolicy(DEFAULT_MAX_CHARACTERS, DEFAULT_OVERLAP_CHARACTERS, SeparatorMode.PARAGRAPH);
    }

    public enum SeparatorMode {
        PARAGRAPH,
        SENTENCE,
        FIXED
    }
}
