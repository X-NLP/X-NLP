package com.xnlp.server.dataset.versioning;

/** Stable application error raised by the versioned Dataset service. */
public final class DatasetVersioningException extends RuntimeException {

    private final Reason reason;
    private final Long currentVersion;

    private DatasetVersioningException(Reason reason, String message, Long currentVersion) {
        super(message);
        this.reason = reason;
        this.currentVersion = currentVersion;
    }

    public static DatasetVersioningException datasetNotFound() {
        return new DatasetVersioningException(Reason.DATASET_NOT_FOUND, "Dataset was not found", null);
    }

    public static DatasetVersioningException entryNotFound() {
        return new DatasetVersioningException(Reason.DATASET_ENTRY_NOT_FOUND, "Dataset entry was not found", null);
    }

    public static DatasetVersioningException versionConflict(long currentVersion) {
        return new DatasetVersioningException(
                Reason.DATASET_VERSION_CONFLICT,
                "Dataset version does not match the expected version",
                currentVersion);
    }

    public static DatasetVersioningException importNotFound() {
        return new DatasetVersioningException(Reason.DATASET_IMPORT_NOT_FOUND, "Dataset import was not found", null);
    }

    public Reason reason() {
        return reason;
    }

    public Long currentVersion() {
        return currentVersion;
    }

    public enum Reason {
        DATASET_NOT_FOUND,
        DATASET_ENTRY_NOT_FOUND,
        DATASET_VERSION_CONFLICT,
        DATASET_IMPORT_NOT_FOUND
    }
}
