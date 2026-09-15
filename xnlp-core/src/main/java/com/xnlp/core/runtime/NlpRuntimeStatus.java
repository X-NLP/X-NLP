package com.xnlp.core.runtime;

import java.time.Instant;
import java.util.Objects;

/** Immutable health snapshot for an NLP runtime. */
public record NlpRuntimeStatus(NlpRuntimeState state, String message, Instant updatedAt) {

    public NlpRuntimeStatus {
        state = Objects.requireNonNull(state, "state must not be null");
        message = message == null || message.isBlank() ? state.name().toLowerCase() : message.strip();
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static NlpRuntimeStatus of(NlpRuntimeState state, String message) {
        return new NlpRuntimeStatus(state, message, Instant.now());
    }
}
