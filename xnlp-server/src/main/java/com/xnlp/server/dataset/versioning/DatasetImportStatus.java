package com.xnlp.server.dataset.versioning;

public enum DatasetImportStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == FAILED || this == CANCELLED;
    }
}
