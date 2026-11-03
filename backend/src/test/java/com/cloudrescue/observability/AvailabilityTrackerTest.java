package com.cloudrescue.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AvailabilityTrackerTest {
    @Test
    void noSamplesMeansNotANumberNotZero() {
        AvailabilityTracker t = new AvailabilityTracker(10);
        assertTrue(Double.isNaN(t.ratio("payment-service")));
        assertEquals(0, t.sampleCount("payment-service"));
    }

    @Test
    void ratioIsSuccessesOverSamples() {
        AvailabilityTracker t = new AvailabilityTracker(10);
        for (int i = 0; i < 8; i++) t.record("a", true);
        t.record("a", false);
        t.record("a", false);
        assertEquals(0.8, t.ratio("a"), 1e-9);
    }

    @Test
    void oldSamplesFallOutOfTheWindow() {
        AvailabilityTracker t = new AvailabilityTracker(4);
        t.record("a", false);
        t.record("a", false);
        for (int i = 0; i < 4; i++) t.record("a", true);
        assertEquals(4, t.sampleCount("a"));
        assertEquals(1.0, t.ratio("a"), 1e-9, "the two failures are older than the window");
    }

    @Test
    void servicesAreTrackedIndependently() {
        AvailabilityTracker t = new AvailabilityTracker(10);
        t.record("a", true);
        t.record("b", false);
        assertEquals(1.0, t.ratio("a"), 1e-9);
        assertEquals(0.0, t.ratio("b"), 1e-9);
        assertEquals(2, t.services().size());
    }

    @Test
    void anOutageStaysVisibleAfterRecovery() {
        AvailabilityTracker t = new AvailabilityTracker(10);
        for (int i = 0; i < 3; i++) t.record("a", false);
        for (int i = 0; i < 7; i++) t.record("a", true);
        assertEquals(0.7, t.ratio("a"), 1e-9);
    }

    @Test
    void rejectsAnInvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new AvailabilityTracker(0));
    }
}
