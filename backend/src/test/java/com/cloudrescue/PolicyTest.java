package com.cloudrescue;

import com.cloudrescue.policy.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyTest {
    private static PolicyContext ctx(FailureType t, int retries, int restarts, int failovers, long elapsed, boolean backup) {
        return new PolicyContext(t, retries, restarts, failovers, elapsed, backup);
    }

    @Test
    void fixedRetryRetriesThenGivesUp() {
        var p = new FixedRetryPolicy();
        assertEquals(ActionType.RETRY, p.decide(ctx(FailureType.UNAVAILABLE, 0, 0, 0, 0, true)).action());
        assertEquals(3000, p.decide(ctx(FailureType.UNAVAILABLE, 4, 0, 0, 0, true)).delayMs());
        assertEquals(ActionType.GIVE_UP, p.decide(ctx(FailureType.UNAVAILABLE, 5, 0, 0, 0, true)).action());
    }

    @Test
    void exponentialBackoffDoublesDelayThenRestarts() {
        var p = new ExponentialBackoffPolicy();
        assertEquals(2000, p.decide(ctx(FailureType.UNAVAILABLE, 0, 0, 0, 0, true)).delayMs());
        assertEquals(4000, p.decide(ctx(FailureType.UNAVAILABLE, 1, 0, 0, 0, true)).delayMs());
        assertEquals(16000, p.decide(ctx(FailureType.UNAVAILABLE, 3, 0, 0, 0, true)).delayMs());
        assertEquals(ActionType.RESTART, p.decide(ctx(FailureType.UNAVAILABLE, 4, 0, 0, 0, true)).action());
        assertEquals(ActionType.GIVE_UP, p.decide(ctx(FailureType.UNAVAILABLE, 4, 2, 0, 0, true)).action());
    }

    @Test
    void costAwareFailsOverImmediatelyOnRegionFailure() {
        var d = new CostAwarePolicy().decide(ctx(FailureType.REGION_SUSPECTED, 0, 0, 0, 0, true));
        assertEquals(ActionType.FAILOVER, d.action());
        assertTrue(d.rejected().stream().anyMatch(s -> s.startsWith("RESTART")));
    }

    @Test
    void failoverIsRejectedWhenNoHealthyBackup() {
        var d = new CostAwarePolicy().decide(ctx(FailureType.REGION_SUSPECTED, 0, 0, 0, 0, false));
        assertEquals(ActionType.ESCALATE, d.action());
    }

    @Test
    void costAwareRetriesOnceThenRestartsCrash() {
        var p = new CostAwarePolicy();
        assertEquals(ActionType.RETRY, p.decide(ctx(FailureType.UNAVAILABLE, 0, 0, 0, 0, true)).action());
        assertEquals(ActionType.RESTART, p.decide(ctx(FailureType.UNAVAILABLE, 1, 0, 0, 5, true)).action());
        assertEquals(ActionType.FAILOVER, p.decide(ctx(FailureType.UNAVAILABLE, 1, 2, 0, 30, true)).action());
        assertEquals(ActionType.GIVE_UP, p.decide(ctx(FailureType.UNAVAILABLE, 1, 2, 0, 30, false)).action());
    }

    @Test
    void loopGuardsStopRunawayRecovery() {
        var p = new ExponentialBackoffPolicy();
        assertEquals(ActionType.GIVE_UP, p.decide(ctx(FailureType.UNAVAILABLE, 4, 4, 0, 10, true)).action());
        assertEquals(ActionType.ESCALATE, p.decide(ctx(FailureType.UNAVAILABLE, 0, 0, 0, 241, true)).action());
    }
}
