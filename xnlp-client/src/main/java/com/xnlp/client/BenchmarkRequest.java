package com.xnlp.client;

import java.util.Objects;

/** Typed request contract for a benchmark run. */
public record BenchmarkRequest(int requests, int concurrency, String text) {

    public BenchmarkRequest {
        if (requests < 1 || requests > 10_000) {
            throw new IllegalArgumentException("requests must be between 1 and 10000");
        }
        if (concurrency < 1 || concurrency > 256) {
            throw new IllegalArgumentException("concurrency must be between 1 and 256");
        }
        text = Objects.requireNonNull(text, "text");
        if (text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        if (text.length() > 10_000) throw new IllegalArgumentException("text must not exceed 10000 characters");
    }

    public static BenchmarkRequest defaults() {
        return new BenchmarkRequest(100, 4,
                "The future of natural language processing is bright.");
    }
}
