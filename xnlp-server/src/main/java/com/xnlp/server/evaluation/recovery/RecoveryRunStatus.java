package com.xnlp.server.evaluation.recovery;

public enum RecoveryRunStatus {
    QUEUED,
    RUNNING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
