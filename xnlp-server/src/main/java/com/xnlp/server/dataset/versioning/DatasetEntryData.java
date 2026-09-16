package com.xnlp.server.dataset.versioning;

import java.util.Map;

public record DatasetEntryData(
        String id,
        int sequence,
        String input,
        String expectedOutput,
        Map<String, Object> labels,
        Map<String, Object> metadata) {

    public DatasetEntryData {
        id = DatasetVersioningSupport.text(id, "id", 64);
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        input = DatasetVersioningSupport.text(input, "input", Integer.MAX_VALUE);
        expectedOutput = DatasetVersioningSupport.nullableText(expectedOutput, Integer.MAX_VALUE);
        labels = DatasetVersioningSupport.map(labels);
        metadata = DatasetVersioningSupport.map(metadata);
    }
}
