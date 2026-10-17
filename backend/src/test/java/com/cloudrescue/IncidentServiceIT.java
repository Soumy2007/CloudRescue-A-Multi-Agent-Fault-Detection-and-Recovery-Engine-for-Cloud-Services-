package com.cloudrescue;

import com.cloudrescue.common.Errors.InvalidTransitionException;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "cloudrescue.workers.enabled=false")
class IncidentServiceIT extends PostgresIT {
    @Autowired IncidentService incidents;
    @Autowired ServiceNodeRepository nodes;

    private Incident open() {
        ServiceNode n = nodes.findByServiceNameAndActiveTrue("payment-service").orElseThrow();
        return incidents.open(n, FailureType.UNAVAILABLE, "COST_AWARE", null, "test");
    }

    @Test
    void recordsEveryTransitionWithTimestamp() {
        Incident i = open();
        incidents.transition(i.id, IncidentState.DETECTED, IncidentState.ANALYZING, "a");
        incidents.transition(i.id, IncidentState.ANALYZING, IncidentState.REMEDIATING, "b");
        incidents.transition(i.id, IncidentState.REMEDIATING, IncidentState.RECOVERED, "c");
        var history = incidents.history(i.id);
        assertEquals(4, history.size());                       // null->DETECTED + 3
        assertEquals(IncidentState.RECOVERED, history.get(3).toState);
        assertNotNull(incidents.get(i.id).recoveredAt);
    }

    @Test
    void rejectsInvalidTransition() {
        Incident i = open();
        assertThrows(InvalidTransitionException.class,
                () -> incidents.transition(i.id, IncidentState.DETECTED, IncidentState.RECOVERED, "skip"));
        assertEquals(IncidentState.DETECTED, incidents.get(i.id).state);
    }

    @Test
    void databasePreventsDuplicateOpenIncidentForSameService() {
        open();
        assertThrows(DataIntegrityViolationException.class, this::open);
    }

    @Test
    void concurrentConflictingTransitionsOnlyOneWins() throws Exception {
        Incident i = open();
        incidents.transition(i.id, IncidentState.DETECTED, IncidentState.ANALYZING, "go");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        IncidentState[] targets = {IncidentState.REMEDIATING, IncidentState.ESCALATED};
        java.util.List<Future<?>> fs = new java.util.ArrayList<>();
        for (IncidentState target : targets) {
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    incidents.transition(i.id, IncidentState.ANALYZING, target, "race");
                    wins.incrementAndGet();
                } catch (RuntimeException expected) { /* lost the race */ }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, wins.get());
        assertEquals(3, incidents.history(i.id).size());       // null->DETECTED, ->ANALYZING, one winner
    }

    @Test
    void reopenOnlyAllowedFromFailedOrEscalated() {
        Incident i = open();
        assertThrows(InvalidTransitionException.class, () -> incidents.reopen(i.id, null));
        incidents.transition(i.id, IncidentState.DETECTED, IncidentState.ANALYZING, "a");
        incidents.transition(i.id, IncidentState.ANALYZING, IncidentState.FAILED, "gave up");
        Incident reopened = incidents.reopen(i.id, ActionType.FAILOVER);
        assertEquals(IncidentState.ANALYZING, reopened.state);
        assertEquals(1, reopened.epoch);
        assertEquals("FAILOVER", reopened.forcedAction);
    }
}
