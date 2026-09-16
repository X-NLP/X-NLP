package com.xnlp.server.pipeline.persistence;

public enum PipelineRunStatus {
    QUEUED,
    RUNNING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(PipelineRunStatus next) {
        if (next == null || terminal()) return false;
        return switch (this) {
            case QUEUED -> next == RUNNING || next == CANCELLING || next == FAILED || next == CANCELLED;
            case RUNNING -> next == CANCELLING || next == COMPLETED || next == FAILED || next == CANCELLED;
            case CANCELLING -> next == CANCELLED || next == FAILED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
