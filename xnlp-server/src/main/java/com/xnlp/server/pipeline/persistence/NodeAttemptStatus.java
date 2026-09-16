package com.xnlp.server.pipeline.persistence;

public enum NodeAttemptStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean terminal() {
        return this != RUNNING;
    }
}
