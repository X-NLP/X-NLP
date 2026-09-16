package com.xnlp.server.dataset.versioning;

import java.util.Optional;

public record DatasetWriteResult<T>(DatasetWriteStatus status, T value, Long currentVersion) {

    public DatasetWriteResult {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (status == DatasetWriteStatus.APPLIED && value == null) {
            throw new IllegalArgumentException("An applied write must return a value");
        }
        if (status == DatasetWriteStatus.VERSION_CONFLICT && currentVersion == null) {
            throw new IllegalArgumentException("A conflict must expose the current version");
        }
    }

    public static <T> DatasetWriteResult<T> applied(T value, long currentVersion) {
        return new DatasetWriteResult<>(DatasetWriteStatus.APPLIED, value, currentVersion);
    }

    public static <T> DatasetWriteResult<T> notFound() {
        return new DatasetWriteResult<>(DatasetWriteStatus.NOT_FOUND, null, null);
    }

    public static <T> DatasetWriteResult<T> conflict(long currentVersion) {
        return new DatasetWriteResult<>(DatasetWriteStatus.VERSION_CONFLICT, null, currentVersion);
    }

    public boolean applied() {
        return status == DatasetWriteStatus.APPLIED;
    }

    public Optional<T> optionalValue() {
        return Optional.ofNullable(value);
    }
}
