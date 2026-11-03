package com.cloudrescue.observability;

import java.util.Collections;
import java.util.List;

/** Small pure helpers for the observability summary. */
public final class RecoveryStats {
    private RecoveryStats() {}

    /** mean/median/max are null when n == 0, so the JSON never carries NaN. */
    public record DurationSummary(Double meanSec, Double medianSec, Double maxSec, int n) {}

    /**
     * Share of FINISHED incidents that recovered: recovered / (recovered + failed + escalated).
     * Incidents still being worked on are excluded. Returns null when nothing has finished.
     */
    public static Double successRate(long recovered, long failed, long escalated) {
        long finished = recovered + failed + escalated;
        return finished == 0 ? null : (double) recovered / finished;
    }

    public static DurationSummary summarize(List<Double> seconds) {
        if (seconds.isEmpty()) return new DurationSummary(null, null, null, 0);
        List<Double> sorted = seconds.stream().sorted().toList();
        int n = sorted.size();
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return new DurationSummary(mean, median, Collections.max(sorted), n);
    }
}
