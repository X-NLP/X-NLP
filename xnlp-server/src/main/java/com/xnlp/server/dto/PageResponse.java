package com.xnlp.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Consistent zero-based page contract for collection endpoints. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        int totalPages,
        boolean hasNext) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PageResponse<>(items, page, size, total, totalPages, page + 1 < totalPages);
    }

    /** Compatibility alias retained while existing web and Java clients migrate to {@code items}. */
    @JsonProperty("entries")
    public List<T> entries() {
        return items;
    }
}
