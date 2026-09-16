package com.xnlp.server.quota;

import java.time.Instant;
import java.util.Objects;

public record QuotaClaim(
        boolean granted,
        long usedUnits,
        long limitUnits,
        Instant windowEnd) {

    public QuotaClaim {
        if (usedUnits < 0 || limitUnits < 0) throw new IllegalArgumentException("usage and limit must not be negative");
        Objects.requireNonNull(windowEnd, "windowEnd");
    }

    public long remainingUnits() {
        return Math.max(0, limitUnits - usedUnits);
    }
}
