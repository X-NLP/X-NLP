package com.xnlp.server.quota;

import java.util.Arrays;

public enum QuotaDimension {
    REQUEST("request"),
    MODEL_CALL("model_call"),
    KNOWLEDGE_IMPORT("knowledge_import");

    private final String storageValue;

    QuotaDimension(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }

    public static QuotaDimension fromStorageValue(String value) {
        return Arrays.stream(values())
                .filter(dimension -> dimension.storageValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown quota dimension: " + value));
    }
}
