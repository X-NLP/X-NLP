package com.xnlp.server.evaluation.recovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationRecoveryDomainTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void run_RejectsInvalidLineageAndTerminalShape() {
        assertThatThrownBy(() -> new RecoveryRun(
                "tenant-a", "run-1", "dataset", 0, RecoveryRunStatus.QUEUED,
                "other", null, 0, false, 1, "alice", null, NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryRun(
                "tenant-a", "run-1", "dataset", 0, RecoveryRunStatus.COMPLETED,
                "run-1", null, 0, false, 1, "alice", null, NOW, NOW, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecoveryRun(
                "tenant-a", "run-1", "dataset", 0, RecoveryRunStatus.RUNNING,
                "run-1", null, 0, false, 1, "alice", null, NOW, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sampleAndCheckpoint_RejectInconsistentState() {
        assertThatThrownBy(() -> new EvaluationSampleResult(
                "tenant-a", "run-1", "sample-1", 0, SampleResultStatus.FAILED,
                null, null, "{}", null, null, "key", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationCheckpoint(
                "tenant-a", "run-1", 1, 2, 1, 0, "sample-1", 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryLease(
                "tenant-a", "run-1", "worker", 0, NOW, NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
