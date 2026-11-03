package com.cloudrescue.observability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryStatsTest {
    @Test
    void successRateIsNullWhenNothingHasFinished() {
        assertNull(RecoveryStats.successRate(0, 0, 0));
    }

    @Test
    void successRateCountsOnlyFinishedIncidents() {
        assertEquals(0.5, RecoveryStats.successRate(2, 1, 1), 1e-9);
        assertEquals(1.0, RecoveryStats.successRate(3, 0, 0), 1e-9);
        assertEquals(0.0, RecoveryStats.successRate(0, 2, 0), 1e-9);
    }

    @Test
    void emptyDurationsAreUndefinedNotZero() {
        RecoveryStats.DurationSummary s = RecoveryStats.summarize(List.of());
        assertEquals(0, s.n());
        assertNull(s.meanSec());
        assertNull(s.medianSec());
        assertNull(s.maxSec());
    }

    @Test
    void summarizesMeanMedianMax() {
        RecoveryStats.DurationSummary odd = RecoveryStats.summarize(List.of(5.0, 1.0, 3.0));
        assertEquals(3.0, odd.meanSec(), 1e-9);
        assertEquals(3.0, odd.medianSec(), 1e-9);
        assertEquals(5.0, odd.maxSec(), 1e-9);
        assertEquals(3, odd.n());
        assertEquals(2.5, RecoveryStats.summarize(List.of(1.0, 2.0, 3.0, 4.0)).medianSec(), 1e-9);
    }
}
