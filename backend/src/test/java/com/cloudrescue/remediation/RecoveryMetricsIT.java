package com.cloudrescue.remediation;

import com.cloudrescue.PostgresIT;
import com.cloudrescue.fault.FaultService;
import com.cloudrescue.fault.FaultType;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.observability.AfterCommit;
import com.cloudrescue.observability.RecoveryMetrics;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static com.cloudrescue.incident.IncidentState.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Observability of the real recovery engine against PostgreSQL. Background workers are off; each test drives
 * RecoveryWorker.step() itself. The registry is shared and cumulative, so every assertion compares before/after.
 */
@SpringBootTest(properties = "cloudrescue.workers.enabled=false")
class RecoveryMetricsIT extends PostgresIT {
    private static final String PAYMENT = "payment-service";

    @Autowired RecoveryWorker worker;
    @Autowired IncidentService incidents;
    @Autowired FaultService faults;
    @Autowired ServiceNodeRepository nodes;
    @Autowired MeterRegistry registry;
    @Autowired RecoveryMetrics metrics;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void resetFaults() {
        faults.reset();
    }

    // ---------- helpers ----------

    private ServiceNode node(String region) {
        return nodes.findByServiceName(PAYMENT).stream().filter(n -> n.region.equals(region)).findFirst().orElseThrow();
    }

    private Incident open(FailureType type, String policy) {
        return incidents.open(node("A"), type, policy, null, "test");
    }

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

    private Incident reopenedWith(ActionType forced) {
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");
        incidents.transition(i.id, DETECTED, ANALYZING, "t");
        incidents.transition(i.id, ANALYZING, FAILED, "t");
        incidents.reopen(i.id, forced);
        return incidents.get(i.id);
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private long timerCount(String path) {
        Timer t = registry.find("cloudrescue.recovery.duration").tag("path", path).timer();
        return t == null ? 0 : t.count();
    }

    private double allCounters() {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("cloudrescue.") && m instanceof Counter)
                .mapToDouble(m -> ((Counter) m).count()).sum();
    }

    // ---------- tests ----------

    @Test
    void successfulRecoveryIsObservedExactlyOnce() {
        faults.inject(FaultType.CRASH, node("A").id, null, null, null);
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");
        double verified = count("cloudrescue.recovery.verification", "outcome", "verified");
        double actionFailed = count("cloudrescue.recovery.verification", "outcome", "action_failed");
        double retryFailure = count("cloudrescue.recovery.attempts", "action", "retry", "result", "failure");
        double restartSuccess = count("cloudrescue.recovery.attempts", "action", "restart", "result", "success");
        double retries = count("cloudrescue.recovery.retries");
        double recovered = count("cloudrescue.incidents.closed", "state", "recovered");
        double decRetry = count("cloudrescue.policy.decisions", "policy", "cost_aware", "decision", "retry");
        double decRestart = count("cloudrescue.policy.decisions", "policy", "cost_aware", "decision", "restart");
        long durations = timerCount("automatic");

        assertEquals(RECOVERED, driveToTerminal(i.id, 20).state);

        // The retry hit a crashed node (action failed); the restart succeeded and was verified.
        assertEquals(1, count("cloudrescue.recovery.verification", "outcome", "action_failed") - actionFailed, 0.0);
        assertEquals(1, count("cloudrescue.recovery.verification", "outcome", "verified") - verified, 0.0);
        assertEquals(1, count("cloudrescue.recovery.attempts", "action", "retry", "result", "failure") - retryFailure, 0.0);
        assertEquals(1, count("cloudrescue.recovery.attempts", "action", "restart", "result", "success") - restartSuccess, 0.0);
        assertEquals(1, count("cloudrescue.recovery.retries") - retries, 0.0, "the restart decision followed an earlier action");
        assertEquals(1, count("cloudrescue.incidents.closed", "state", "recovered") - recovered, 0.0);
        assertEquals(1, count("cloudrescue.policy.decisions", "policy", "cost_aware", "decision", "retry") - decRetry, 0.0);
        assertEquals(1, count("cloudrescue.policy.decisions", "policy", "cost_aware", "decision", "restart") - decRestart, 0.0);
        assertEquals(1, timerCount("automatic") - durations);
    }

    @Test
    void failedRecoveryIsObservedAsFailureAndNeverAsRecovered() {
        // Primary A is healthy, backup B is crashed, an operator forces a failover (the Phase 2 false-recovery case).
        faults.inject(FaultType.CRASH, node("B").id, null, null, null);
        Incident i = reopenedWith(ActionType.FAILOVER);
        double verified = count("cloudrescue.recovery.verification", "outcome", "verified");
        double actionFailed = count("cloudrescue.recovery.verification", "outcome", "action_failed");
        double failoverFailure = count("cloudrescue.recovery.attempts", "action", "failover", "result", "failure");
        double recovered = count("cloudrescue.incidents.closed", "state", "recovered");
        double operatorDecisions = count("cloudrescue.policy.decisions", "policy", "operator", "decision", "failover");
        long durations = timerCount("automatic") + timerCount("operator_reopened");

        Incident after = steps(i.id, 2);

        assertNotEquals(RECOVERED, after.state);
        assertEquals(1, count("cloudrescue.recovery.verification", "outcome", "action_failed") - actionFailed, 0.0);
        assertEquals(0, count("cloudrescue.recovery.verification", "outcome", "verified") - verified, 0.0);
        assertEquals(1, count("cloudrescue.recovery.attempts", "action", "failover", "result", "failure") - failoverFailure, 0.0);
        assertEquals(0, count("cloudrescue.incidents.closed", "state", "recovered") - recovered, 0.0);
        assertEquals(1, count("cloudrescue.policy.decisions", "policy", "operator", "decision", "failover") - operatorDecisions, 0.0);
        assertEquals(durations, timerCount("automatic") + timerCount("operator_reopened"), "no recovery duration recorded");
    }

    @Test
    void repeatedStepsOnAFinishedIncidentDoNotInflateMetrics() {
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");          // node healthy: the first retry recovers it
        assertEquals(RECOVERED, driveToTerminal(i.id, 10).state);
        double before = allCounters();
        long durations = timerCount("automatic");

        steps(i.id, 3);                                                     // RECOVERED is terminal: no-ops

        assertEquals(before, allCounters(), 0.0);
        assertEquals(durations, timerCount("automatic"));
    }

    @Test
    void observationsHappenOnlyAfterCommit() {
        TransactionTemplate template = new TransactionTemplate(txManager);
        double before = count("cloudrescue.incidents.detected", "failure_type", "unavailable");

        assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(s -> {
            AfterCommit.run(() -> metrics.incidentDetected(FailureType.UNAVAILABLE));
            throw new IllegalStateException("force a rollback");
        }));
        assertEquals(before, count("cloudrescue.incidents.detected", "failure_type", "unavailable"), 0.0,
                "a rolled-back transaction must not be counted");

        template.executeWithoutResult(s -> AfterCommit.run(() -> metrics.incidentDetected(FailureType.UNAVAILABLE)));
        assertEquals(before + 1, count("cloudrescue.incidents.detected", "failure_type", "unavailable"), 0.0);

        AfterCommit.run(() -> metrics.incidentDetected(FailureType.UNAVAILABLE));   // no transaction: immediate
        assertEquals(before + 2, count("cloudrescue.incidents.detected", "failure_type", "unavailable"), 0.0);
    }

    @Test
    void activeIncidentsGaugeTracksUnresolvedIncidentsInPostgres() {
        assertEquals(0.0, registry.get("cloudrescue.active.incidents").gauge().value(), 0.0);
        Incident i = open(FailureType.UNAVAILABLE, "COST_AWARE");
        assertEquals(1.0, registry.get("cloudrescue.active.incidents").gauge().value(), 0.0);
        assertEquals(RECOVERED, driveToTerminal(i.id, 10).state);
        assertEquals(0.0, registry.get("cloudrescue.active.incidents").gauge().value(), 0.0);
    }

    @Test
    void availabilityGaugeExistsForEveryService() {
        for (String service : List.of("payment-service", "order-service", "auth-service")) {
            assertNotNull(registry.find("cloudrescue.service.availability.ratio").tag("service", service).gauge(),
                    "missing availability gauge for " + service);
        }
    }

    @Test
    void metricLabelsStayBoundedInARealRun() {
        faults.inject(FaultType.CRASH, node("A").id, null, null, null);
        assertEquals(RECOVERED, driveToTerminal(open(FailureType.UNAVAILABLE, "COST_AWARE").id, 20).state);

        Set<String> allowedKeys = Set.of("application", "failure_type", "policy", "decision", "action", "result",
                "outcome", "state", "path", "service");
        for (Meter m : registry.getMeters()) {
            if (!m.getId().getName().startsWith("cloudrescue.")) continue;
            for (Tag t : m.getId().getTags()) {
                if (t.getKey().equals("le")) {   // histogram bucket boundary: a fixed, bounded set of numbers or +Inf
                    assertTrue(t.getValue().matches("\\+Inf|[0-9.eE+-]+"), "unexpected histogram bucket boundary " + t.getValue());
                    continue;
                }
                assertTrue(allowedKeys.contains(t.getKey()), "unexpected label key " + t.getKey());
                assertTrue(t.getValue().matches("[a-z_\\-]+"),
                        "label values must be bounded words, found " + t.getKey() + "=" + t.getValue());
            }
        }
    }
}
