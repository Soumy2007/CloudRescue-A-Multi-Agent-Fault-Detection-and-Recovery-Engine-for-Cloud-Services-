package com.cloudrescue.observability;

import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.remediation.VerificationOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests of metric semantics, with a plain in-memory registry (no Spring, no database). */
class RecoveryMetricsTest {
    private SimpleMeterRegistry registry;
    private RecoveryMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RecoveryMetrics(registry);
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void detectionIsCountedPerFailureType() {
        metrics.incidentDetected(FailureType.UNAVAILABLE);
        metrics.incidentDetected(FailureType.UNAVAILABLE);
        metrics.incidentDetected(FailureType.REGION_SUSPECTED);
        assertEquals(2, count("cloudrescue.incidents.detected", "failure_type", "unavailable"));
        assertEquals(1, count("cloudrescue.incidents.detected", "failure_type", "region_suspected"));
    }

    @Test
    void successAndFailureOfAttemptsAreCountedSeparately() {
        metrics.recoveryAttempt(ActionType.RESTART, true);
        metrics.recoveryAttempt(ActionType.RESTART, false);
        metrics.recoveryAttempt(ActionType.RESTART, false);
        assertEquals(1, count("cloudrescue.recovery.attempts", "action", "restart", "result", "success"));
        assertEquals(2, count("cloudrescue.recovery.attempts", "action", "restart", "result", "failure"));
    }

    @Test
    void verificationFailureIsObservable() {
        metrics.verification(VerificationOutcome.VERIFIED);
        metrics.verification(VerificationOutcome.ACTION_FAILED);
        metrics.verification(VerificationOutcome.POSTCONDITION_FAILED);
        assertEquals(1, count("cloudrescue.recovery.verification", "outcome", "verified"));
        assertEquals(1, count("cloudrescue.recovery.verification", "outcome", "action_failed"));
        assertEquals(1, count("cloudrescue.recovery.verification", "outcome", "postcondition_failed"));
    }

    @Test
    void durationIsRecordedInSecondsAndSplitByPath() {
        metrics.recoveryDuration(Duration.ofMillis(12_500), false);
        metrics.recoveryDuration(Duration.ofSeconds(900), true);
        Timer auto = registry.find("cloudrescue.recovery.duration").tag("path", "automatic").timer();
        Timer reopened = registry.find("cloudrescue.recovery.duration").tag("path", "operator_reopened").timer();
        assertNotNull(auto);
        assertEquals(1, auto.count());
        assertEquals(12.5, auto.totalTime(TimeUnit.SECONDS), 1e-6);
        assertEquals(900.0, reopened.totalTime(TimeUnit.SECONDS), 1e-6);
    }

    @Test
    void retriesCountOnlyDecisionsTakenAfterAnEarlierAction() {
        metrics.policyDecision("COST_AWARE", ActionType.RETRY, false);
        assertEquals(0, count("cloudrescue.recovery.retries"));
        metrics.policyDecision("COST_AWARE", ActionType.RESTART, true);
        assertEquals(1, count("cloudrescue.recovery.retries"));
        assertEquals(1, count("cloudrescue.policy.decisions", "policy", "cost_aware", "decision", "restart"));
    }

    @Test
    void onlyFinalStatesAreCountedAsClosed() {
        metrics.incidentClosed(IncidentState.RECOVERED);
        metrics.incidentClosed(IncidentState.FAILED);
        metrics.incidentClosed(IncidentState.ESCALATED);
        metrics.incidentClosed(IncidentState.ANALYZING);   // not a final state: ignored
        assertEquals(1, count("cloudrescue.incidents.closed", "state", "recovered"));
        assertEquals(1, count("cloudrescue.incidents.closed", "state", "failed"));
        assertEquals(1, count("cloudrescue.incidents.closed", "state", "escalated"));
        assertEquals(0, count("cloudrescue.incidents.closed", "state", "analyzing"));
    }

    @Test
    void labelsStayBoundedAndNeverContainIdentifiers() {
        for (FailureType f : FailureType.values()) metrics.incidentDetected(f);
        for (ActionType a : ActionType.values()) metrics.policyDecision("FIXED_RETRY", a, true);
        for (ActionType a : ActionType.values()) metrics.recoveryAttempt(a, true);
        for (VerificationOutcome o : VerificationOutcome.values()) metrics.verification(o);
        metrics.recoveryDuration(Duration.ofSeconds(3), false);

        Set<String> allowedKeys = Set.of("failure_type", "policy", "decision", "action", "result", "outcome", "state", "path");
        for (Meter m : registry.getMeters()) {
            for (Tag t : m.getId().getTags()) {
                if (t.getKey().equals("le")) {   // histogram bucket boundary: a fixed, bounded set of numbers or +Inf
                    assertTrue(t.getValue().matches("\\+Inf|[0-9.eE+-]+"), "unexpected histogram bucket boundary " + t.getValue());
                    continue;
                }
                assertTrue(allowedKeys.contains(t.getKey()), "unexpected label key " + t.getKey());
                assertTrue(t.getValue().matches("[a-z_]+"), "label value must be a bounded word, was " + t.getValue());
            }
        }
        assertTrue(registry.getMeters().size() < 60, "label combinations must stay small");
    }

    @Test
    void aBrokenRegistryNeverBreaksTheCaller() {
        RecoveryMetrics broken = new RecoveryMetrics(null);
        assertDoesNotThrow(() -> broken.incidentDetected(FailureType.UNAVAILABLE));
        assertDoesNotThrow(() -> broken.recoveryAttempt(ActionType.RESTART, true));
        assertDoesNotThrow(() -> broken.recoveryDuration(Duration.ofSeconds(1), false));
        assertDoesNotThrow(() -> broken.safely(() -> { throw new IllegalStateException("registry down"); }));
    }
}
