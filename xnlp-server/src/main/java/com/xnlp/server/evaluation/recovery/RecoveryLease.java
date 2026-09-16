package com.xnlp.server.evaluation.recovery;

import java.time.Instant;

public record RecoveryLease(
        String tenantId,
        String runId,
        String ownerId,
        long fencingToken,
        Instant acquiredAt,
        Instant renewedAt,
        Instant expiresAt) {

    public RecoveryLease {
        tenantId = EvaluationRecoverySupport.tenantId(tenantId);
        runId = EvaluationRecoverySupport.text(runId, "runId", 64);
        ownerId = EvaluationRecoverySupport.text(ownerId, "ownerId", 190);
        EvaluationRecoverySupport.instant(acquiredAt, "acquiredAt");
        EvaluationRecoverySupport.instant(renewedAt, "renewedAt");
        EvaluationRecoverySupport.instant(expiresAt, "expiresAt");
        if (fencingToken < 1) throw new IllegalArgumentException("fencingToken must be positive");
        if (renewedAt.isBefore(acquiredAt)) throw new IllegalArgumentException("renewedAt must not precede acquiredAt");
        if (!expiresAt.isAfter(renewedAt)) throw new IllegalArgumentException("expiresAt must be after renewedAt");
    }

    public boolean activeAt(Instant instant) {
        return expiresAt.isAfter(EvaluationRecoverySupport.instant(instant, "instant"));
    }
}
