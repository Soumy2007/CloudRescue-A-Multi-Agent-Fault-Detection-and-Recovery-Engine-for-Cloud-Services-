package com.cloudrescue;

import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.incident.IncidentTransition;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// edit in update 3/11/2026
import com.cloudrescue.fault.FaultService;
import com.cloudrescue.fault.FaultInjection;
import com.cloudrescue.fault.FaultType;
// upto this point
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.cloudrescue.incident.IncidentState.*;
import static org.junit.jupiter.api.Assertions.*;

/** The rules for re-opening a stale FAILED/ESCALATED incident when its service fails again. */
@SpringBootTest(properties = "cloudrescue.workers.enabled=false")
class IncidentAutoReopenIT extends PostgresIT {
    private static final Duration MIN_AGE = Duration.ofSeconds(30);

    @Autowired IncidentService incidents;
    @Autowired ServiceNodeRepository nodes;

    @Autowired FaultService faultService;

  // edit in 3/11/2026
   private Incident incidentIn(IncidentState finalState) {
    ServiceNode n = nodes.findByServiceNameAndActiveTrue("payment-service").orElseThrow();

    FaultInjection originalFault =
            faultService.inject(FaultType.CRASH, n.id, null, null, null);

    Incident i = incidents.open(
            n, FailureType.UNAVAILABLE, "FIXED_RETRY",
            originalFault.id, "test");

    incidents.transition(i.id, DETECTED, ANALYZING, "t");
    incidents.transition(i.id, ANALYZING, finalState, "final");

    return i;
}
   private Long injectNewFault() {
    ServiceNode n = nodes.findByServiceNameAndActiveTrue("payment-service").orElseThrow();

    return faultService.inject(
            FaultType.CRASH, n.id, null, null, null
    ).id;
}

 // upto this point

    /** Pretend the incident reached its current state this many seconds ago. */
    private void age(long id, int seconds) {
        jdbc.update("UPDATE incident_state_transitions SET occurred_at = occurred_at - interval '1 second' * ? "
                + "WHERE incident_id = ?", seconds, id);
    }

    @Test
    void reopensAStaleFailedIncidentWithTheCurrentPolicyAndFailureType() {
        Incident i = incidentIn(FAILED);
        age(i.id, 120);

Optional<Incident> reopened = incidents.reopenIfStale(
        i.id,
        "COST_AWARE",
        FailureType.REGION_SUSPECTED,
        injectNewFault(),
        MIN_AGE,
        3
);

        assertTrue(reopened.isPresent());
        Incident now = incidents.get(i.id);
        assertEquals(ANALYZING, now.state);
        assertEquals(1, now.epoch, "a new epoch resets the policy's action counters");
        assertEquals("COST_AWARE", now.policyName, "the incident adopts the CURRENT policy");
        assertEquals(FailureType.REGION_SUSPECTED, now.failureType);
        assertNull(now.forcedAction);
        List<IncidentTransition> history = incidents.history(i.id);
        IncidentTransition last = history.get(history.size() - 1);
        assertEquals(ANALYZING, last.toState);
        assertTrue(last.reason.contains("Auto re-opened"), last.reason);
    }

    @Test
    void reopensAStaleEscalatedIncidentToo() {
        Incident i = incidentIn(ESCALATED);
        age(i.id, 120);
        assertTrue(incidents.reopenIfStale( i.id,
        "COST_AWARE",
        FailureType.REGION_SUSPECTED,
        injectNewFault(),
        MIN_AGE,
        3
        ).isPresent());
        assertEquals(ANALYZING, incidents.get(i.id).state);
    }

    @Test
    void leavesARecentlyFinishedIncidentAlone() {
        Incident i = incidentIn(FAILED);                     // finished just now, younger than MIN_AGE
        assertTrue(incidents.reopenIfStale( i.id,
        "COST_AWARE",
        FailureType.REGION_SUSPECTED,
        injectNewFault(),
        MIN_AGE,
        3
           ).isEmpty());
        assertEquals(FAILED, incidents.get(i.id).state);
        assertEquals(0, incidents.get(i.id).epoch);
    }

    @Test
    void stopsAfterTheMaximumNumberOfReopens() {
        Incident i = incidentIn(FAILED);
        age(i.id, 120);
        jdbc.update("UPDATE incidents SET epoch = 3 WHERE id = ?", i.id);
        assertTrue(incidents.reopenIfStale(
            i.id,
            "COST_AWARE",
            FailureType.REGION_SUSPECTED,
            injectNewFault(),
            MIN_AGE,
            3
        ).isEmpty());
        assertEquals(FAILED, incidents.get(i.id).state, "a service that cannot be repaired is not retried forever");
    }

    @Test
    void ignoresIncidentsThatAreStillBeingHandled() {
        ServiceNode n = nodes.findByServiceNameAndActiveTrue("payment-service").orElseThrow();
        Incident i = incidents.open(n, FailureType.UNAVAILABLE, "COST_AWARE", null, "test");   // DETECTED
        age(i.id, 120);
        assertTrue(incidents.reopenIfStale( i.id,
        "COST_AWARE",
        FailureType.REGION_SUSPECTED,
        injectNewFault(),
        MIN_AGE,
        3).isEmpty());
        assertEquals(DETECTED, incidents.get(i.id).state);
    }
}
