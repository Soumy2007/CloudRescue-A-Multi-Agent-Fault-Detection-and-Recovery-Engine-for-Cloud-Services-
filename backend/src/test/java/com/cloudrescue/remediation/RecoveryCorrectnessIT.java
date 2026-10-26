package com.cloudrescue.remediation;

import com.cloudrescue.PostgresIT;
import com.cloudrescue.fault.FaultService;
import com.cloudrescue.fault.FaultType;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.incident.IncidentTransition;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import com.cloudrescue.simulator.SimulatorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.cloudrescue.incident.IncidentState.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for recovery correctness. Background workers are disabled; each test drives
 * RecoveryWorker.step() itself against real PostgreSQL, so outcomes do not depend on timing.
 */
@SpringBootTest(properties = "cloudrescue.workers.enabled=false")
class RecoveryCorrectnessIT extends PostgresIT {
    private static final String PAYMENT = "payment-service";

    @Autowired RecoveryWorker worker;
    @Autowired IncidentService incidents;
    @Autowired FaultService faults;
    @Autowired ServiceNodeRepository nodes;
    @Autowired RemediationActionRepository actions;
    @Autowired RemediationService remediation;
    @Autowired SimulatorService simulator;

    @AfterEach
    void resetFaults() {
        faults.reset();   // also restores the in-memory simulator
    }

    // ---------- helpers ----------

    private ServiceNode node(String region) {
        return nodes.findByServiceName(PAYMENT).stream().filter(n -> n.region.equals(region)).findFirst().orElseThrow();
    }

    private Incident open(FailureType type, String policy) {
        return incidents.open(node("A"), type, policy, null, "test");
    }

    /** Runs up to n worker steps, making any delayed (backoff) action due immediately. */
    private Incident steps(long id, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("UPDATE remediation_actions SET scheduled_at = now() - interval '1 second' "
                    + "WHERE incident_id = ? AND status = 'PENDING'", id);
            worker.step(id);
        }
        return incidents.get(id);
    }

    private Incident driveToTerminal(long id, int maxSteps) {
        for (int i = 0; i < maxSteps; i++) {
            IncidentState s = incidents.get(id).state;
            if (s == RECOVERED || s == FAILED || s == ESCALATED) break;
            steps(id, 1);
        }
        return incidents.get(id);
    }

    /** An incident in ANALYZING that will execute exactly the forced action next. */
    private Incident reopenedWith(ActionType forced) {
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");
        incidents.transition(i.id, DETECTED, ANALYZING, "t");
        incidents.transition(i.id, ANALYZING, FAILED, "t");
        incidents.reopen(i.id, forced);
        return incidents.get(i.id);
    }

    private List<RemediationAction> actionsOf(long id) {
        return actions.findByIncidentIdOrderBySeqNoAsc(id);
    }

    // ---------- required scenarios ----------

    @Test
    void successfulRetryRecoversAfterVerification() {
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");   // transient failure is already gone
        assertEquals(RECOVERED, driveToTerminal(i.id, 10).state);
        List<RemediationAction> as = actionsOf(i.id);
        assertEquals(1, as.size());
        assertEquals(ActionType.RETRY, as.get(0).actionType);
        assertEquals(ActionStatus.SUCCEEDED, as.get(0).status);
        assertEquals(VerificationOutcome.VERIFIED, as.get(0).outcome);
    }

    @Test
    void successfulRestartRecoversACrash() {
        faults.inject(FaultType.CRASH, node("A").id, null, null, null);
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");
        assertEquals(RECOVERED, driveToTerminal(i.id, 20).state);

        List<RemediationAction> as = actionsOf(i.id);
        assertEquals(ActionType.RETRY, as.get(0).actionType);
        assertEquals(VerificationOutcome.ACTION_FAILED, as.get(0).outcome, "retry against a crashed node fails");
        RemediationAction restart = as.get(as.size() - 1);
        assertEquals(ActionType.RESTART, restart.actionType);
        assertEquals(VerificationOutcome.VERIFIED, restart.outcome);
        assertEquals(1, simulator.effectCount(restart.idempotencyKey));
        assertEquals(0, simulator.duplicateEffects());
    }

    @Test
    void successfulFailoverToAHealthyBackup() {
        faults.inject(FaultType.REGION_OUTAGE, null, "A", null, null);
        Incident i = open(FailureType.REGION_SUSPECTED, "COST_AWARE");
        assertEquals(RECOVERED, driveToTerminal(i.id, 20).state);

        RemediationAction f = actionsOf(i.id).get(0);
        assertEquals(ActionType.FAILOVER, f.actionType);
        assertEquals(node("B").id, f.expectedActiveNodeId, "verification targets the intended backup");
        assertEquals(VerificationOutcome.VERIFIED, f.outcome);
        assertTrue(nodes.findById(node("B").id).orElseThrow().active);
        assertFalse(nodes.findById(node("A").id).orElseThrow().active);
    }

    @Test
    void failoverIsNotAttemptedWhenBackupIsUnhealthy_policyEscalates() {
        faults.inject(FaultType.REGION_OUTAGE, null, "A", null, null);
        faults.inject(FaultType.CRASH, node("B").id, null, null, null);
        Incident i = open(FailureType.REGION_SUSPECTED, "COST_AWARE");
        assertEquals(ESCALATED, driveToTerminal(i.id, 10).state);
        assertTrue(actionsOf(i.id).isEmpty());
        assertTrue(nodes.findById(node("A").id).orElseThrow().active, "no traffic was moved");
    }

    @Test
    void failedFailoverIsNotRecoveredEvenWhenTheCurrentNodeIsHealthy() {
        // Primary A is healthy; backup B is crashed. An operator forces a failover.
        // Old behaviour: failover fails, A answers probes, incident wrongly marked RECOVERED.
        faults.inject(FaultType.CRASH, node("B").id, null, null, null);
        Incident i = reopenedWith(ActionType.FAILOVER);

        Incident after = steps(i.id, 2);          // analyze (forced action), then remediate

        RemediationAction a = actionsOf(i.id).get(0);
        assertEquals(ActionType.FAILOVER, a.actionType);
        assertEquals(ActionStatus.FAILED, a.status);
        assertEquals(VerificationOutcome.ACTION_FAILED, a.outcome);
        assertTrue(a.resultMessage.contains("unhealthy"), a.resultMessage);
        assertNotEquals(RECOVERED, after.state);
        assertEquals(ANALYZING, after.state);
        assertTrue(nodes.findById(node("A").id).orElseThrow().active, "traffic must not have moved");
    }

    @Test
    void restartDoesNotRepairARegionOutage_forcedAction() {
        faults.inject(FaultType.REGION_OUTAGE, null, "A", null, null);
        Incident i = reopenedWith(ActionType.RESTART);

        Incident after = steps(i.id, 2);

        RemediationAction a = actionsOf(i.id).get(0);
        assertEquals(ActionStatus.FAILED, a.status);
        assertEquals(VerificationOutcome.ACTION_FAILED, a.outcome);
        assertNotEquals(RECOVERED, after.state);
        assertTrue(simulator.effectResult(a.idempotencyKey).isEmpty(), "no restart effect was applied");
    }

    @Test
    void restartDoesNotRepairARegionOutage_policyNeverRecovers() {
        faults.inject(FaultType.REGION_OUTAGE, null, "A", null, null);
        Incident i = open(FailureType.REGION_SUSPECTED, "EXPONENTIAL_BACKOFF");   // retries + restarts only
        Incident done = driveToTerminal(i.id, 60);
        assertEquals(FAILED, done.state);
        assertTrue(actionsOf(i.id).stream().noneMatch(a -> a.outcome == VerificationOutcome.VERIFIED));
    }

    @Test
    void repeatedRemediationRequestsDoNotDuplicateEffects() {
        faults.inject(FaultType.CRASH, node("A").id, null, null, null);
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");
        RemediationAction a = newAction(i, ActionType.RESTART);

        var first = remediation.execute(i, a);
        var second = remediation.execute(i, a);

        assertTrue(first.actionOk());
        assertTrue(second.actionOk());
        assertTrue(second.message().contains("replayed"), second.message());
        assertEquals(1, simulator.effectCount(a.idempotencyKey));
        assertEquals(0, simulator.duplicateEffects());
    }

    @Test
    void lostAcknowledgementIsReconciledNotRepeated() {
        faults.inject(FaultType.CRASH, node("A").id, null, null, null);
        faults.inject(FaultType.ACK_LOSS, node("A").id, null, 1.0, null);
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");
        RemediationAction a = newAction(i, ActionType.RESTART);

        var r = remediation.execute(i, a);

        assertTrue(r.actionOk());
        assertEquals(2, r.attempts(), "second attempt is the reconciled retry");
        assertTrue(r.message().contains("replayed"), r.message());
        assertEquals(1, simulator.effectCount(a.idempotencyKey));
    }

    @Test
    void concurrentWorkersLeaveConsistentStateAndApplyTheEffectOnce() throws Exception {
        faults.inject(FaultType.CRASH, node("A").id, null, null, null);
        Incident i = reopenedWith(ActionType.RESTART);
        steps(i.id, 1);                                        // analyze -> REMEDIATING with a due RESTART
        assertEquals(REMEDIATING, incidents.get(i.id).state);
        jdbc.update("UPDATE remediation_actions SET scheduled_at = now() - interval '1 second' WHERE incident_id = ?", i.id);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    worker.step(i.id);
                } catch (RuntimeException expectedLoser) { /* conflict or optimistic-lock failure */ }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        driveToTerminal(i.id, 10);                              // resume whatever a losing worker left behind
        assertEquals(RECOVERED, incidents.get(i.id).state);
        assertEquals(1, actionsOf(i.id).size(), "no duplicate action rows");
        String key = RemediationService.keyFor(i.id, 1);
        assertEquals(1, simulator.effectCount(key), "restart applied exactly once");
        assertEquals(0, simulator.duplicateEffects());

        List<IncidentTransition> h = incidents.history(i.id);   // every transition legal and contiguous
        for (int k = 1; k < h.size(); k++) {
            assertEquals(h.get(k - 1).toState, h.get(k).fromState, "history must be a contiguous chain");
            assertTrue(h.get(k).fromState.canTransitionTo(h.get(k).toState));
        }
    }

    private RemediationAction newAction(Incident i, ActionType type) {
        RemediationAction a = new RemediationAction();
        a.incidentId = i.id;
        a.seqNo = 1;
        a.epoch = 0;
        a.actionType = type;
        a.status = ActionStatus.PENDING;
        a.idempotencyKey = RemediationService.keyFor(i.id, 1);
        a.scheduledAt = Instant.now();
        remediation.prepare(i, a);
        return actions.save(a);
    }
}
