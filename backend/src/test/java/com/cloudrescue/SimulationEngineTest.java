package com.cloudrescue;

import com.cloudrescue.experiment.SimulationEngine;
import com.cloudrescue.experiment.SimulationEngine.Scenario;
import com.cloudrescue.policy.CostAwarePolicy;
import com.cloudrescue.policy.ExponentialBackoffPolicy;
import com.cloudrescue.policy.FixedRetryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulationEngineTest {
    private static Scenario scenario(String name) {
        return SimulationEngine.defaultScenarios().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sameSeedGivesIdenticalResults() {
        var s = scenario("CRASH_LOST_ACKS");
        assertEquals(SimulationEngine.run(s, new CostAwarePolicy(), 7, true),
                SimulationEngine.run(s, new CostAwarePolicy(), 7, true));
    }

    @Test
    void fixedRetryCannotRecoverFromACrash() {
        var r = SimulationEngine.run(scenario("SERVICE_CRASH"), new FixedRetryPolicy(), 1, true);
        assertTrue(r.detected());
        assertFalse(r.recovered());
        assertNull(r.recoveryTimeSec());
    }

    @Test
    void costAwareFailsOverOnRegionOutageWhileBackoffCannot() {
        var s = scenario("REGION_OUTAGE");
        var aware = SimulationEngine.run(s, new CostAwarePolicy(), 1, true);
        var backoff = SimulationEngine.run(s, new ExponentialBackoffPolicy(), 1, true);
        assertTrue(aware.recovered());
        assertEquals(1, aware.failovers());
        assertFalse(backoff.recovered());
        assertTrue(aware.availability() > backoff.availability());
    }

    @Test
    void idempotencyPreventsDuplicateOperationsAfterLostAcks() {
        var s = scenario("CRASH_LOST_ACKS");
        int withIdem = 0, withoutIdem = 0;
        for (int i = 0; i < 30; i++) {
            withIdem += SimulationEngine.run(s, new CostAwarePolicy(), 100 + i, true).duplicateOps();
            withoutIdem += SimulationEngine.run(s, new CostAwarePolicy(), 100 + i, false).duplicateOps();
        }
        assertEquals(0, withIdem);
        assertTrue(withoutIdem > 0);
    }

    @Test
    void detectionHappensAfterThreeFailedProbes() {
        var r = SimulationEngine.run(scenario("SERVICE_CRASH"), new CostAwarePolicy(), 3, true);
        assertEquals(2.0, r.detectionDelaySec());   // fault at t=30, 3rd failed probe at t=32
    }

    @Test
    void failedActionIsNotCreditedWithRecoveryWhenALaterProbePasses() {
        // Fault ends at t=37. The first retry (completes t=36) fails; verification at t=37 sees a healthy node.
        // The failed retry must not be credited: recovery comes from the SECOND retry (t=42, verified t=43).
        Scenario s = new Scenario("EXPIRES_DURING_RETRY", "", SimulationEngine.FaultKind.LATENCY_SPIKE,
                30, 7, 0, 0.0, 0.0, true, 300);
        var r = SimulationEngine.run(s, new FixedRetryPolicy(), 1, true);
        assertTrue(r.recovered());
        assertEquals(2, r.retries());
        assertEquals(11.0, r.recoveryTimeSec());     // 43 - 32 (old behaviour would report 5.0)
    }
}
