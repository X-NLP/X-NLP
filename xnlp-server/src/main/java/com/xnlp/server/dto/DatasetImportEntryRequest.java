package com.xnlp.server.dto;

import java.util.Map;

/** Import row. Validation is intentionally performed by the service so bad rows become report items. */
public record DatasetImportEntryRequest(
        String id,
        Integer sequence,
        String input,
        String expectedOutput,
        Map<String, Object> labels,
        Map<String, Object> metadata) {
}
