package com.xnlp.server.evaluation.recovery;

/** Stable application error for resumable evaluation operations. */
public final class EvaluationRecoveryException extends RuntimeException {

    private final Reason reason;

    private EvaluationRecoveryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static EvaluationRecoveryException notFound() {
        return new EvaluationRecoveryException(Reason.EVALUATION_NOT_FOUND, "Evaluation run was not found");
    }

    public static EvaluationRecoveryException notRetryable() {
        return new EvaluationRecoveryException(
                Reason.EVALUATION_NOT_RETRYABLE, "Evaluation run cannot be retried in its current state");
    }

    public static EvaluationRecoveryException queueUnavailable() {
        return new EvaluationRecoveryException(
                Reason.EVALUATION_QUEUE_UNAVAILABLE, "Evaluation queue is unavailable");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        EVALUATION_NOT_FOUND,
        EVALUATION_NOT_RETRYABLE,
        EVALUATION_QUEUE_UNAVAILABLE
    }
}
