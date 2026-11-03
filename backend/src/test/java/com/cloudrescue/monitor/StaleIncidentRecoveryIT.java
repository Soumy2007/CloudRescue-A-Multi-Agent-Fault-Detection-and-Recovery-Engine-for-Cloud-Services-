package com.cloudrescue.monitor;

import com.cloudrescue.PostgresIT;
import com.cloudrescue.fault.FaultService;
import com.cloudrescue.fault.FaultType;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.policy.PolicyRegistry;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the reported problem end to end: an old FAILED incident exists for a service, then that service
 * crashes again. Before the fix nothing acted on the new crash; now the stale incident is re-opened and recovered.
 */
@SpringBootTest(properties = {
        "cloudrescue.workers.enabled=true",
        "cloudrescue.monitor.poll-interval-ms=200",
        "cloudrescue.recovery.tick-ms=200",
        "cloudrescue.recovery.auto-reopen.min-age-ms=0"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaleIncidentRecoveryIT extends PostgresIT {
    @Autowired FaultService faults;
    @Autowired PolicyRegistry policies;
    @Autowired IncidentRepository incidentRepo;
    @Autowired ServiceNodeRepository nodes;

    @AfterEach
    void resetFaults() {
        faults.reset();
    }

    private ServiceNode paymentPrimary() {
        return nodes.findByServiceNameAndActiveTrue("payment-service").orElseThrow();
    }

    /** A FAILED incident that finished long ago. Inserted directly so the background worker cannot touch it. */
    private long staleFailedIncident(ServiceNode node, String policy, int epoch) {
        Long id = jdbc.queryForObject("INSERT INTO incidents (service_name, node_id, failure_type, state, policy_name, "
                + "detected_at, epoch, summary, version) VALUES (?, ?, 'UNAVAILABLE', 'FAILED', ?, "
                + "now() - interval '10 minutes', ?, 'stale failed incident', 0) RETURNING id",
                Long.class, node.serviceName, node.id, policy, epoch);
        jdbc.update("INSERT INTO incident_state_transitions (incident_id, from_state, to_state, reason, occurred_at) "
                + "VALUES (?, 'ANALYZING', 'FAILED', 'gave up', now() - interval '10 minutes')", id);
        return id;
    }

    private long incidentsForPayment() {
        return incidentRepo.findAll().stream().filter(i -> i.serviceName.equals("payment-service")).count();
    }

    @Test
    void aNewCrashIsRecoveredEvenWhenAnOldFailedIncidentIsStillOpen() {
        policies.setActive("COST_AWARE");
        ServiceNode node = paymentPrimary();
        long staleId = staleFailedIncident(node, "FIXED_RETRY", 0);   // would only retry and fail again

        faults.inject(FaultType.CRASH, node.id, null, null, null);

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            Incident i = incidentRepo.findById(staleId).orElseThrow();
            assertEquals(IncidentState.RECOVERED, i.state);
        });
        Incident recovered = incidentRepo.findById(staleId).orElseThrow();
        assertEquals(1, recovered.epoch);
        assertEquals("COST_AWARE", recovered.policyName, "it uses the policy selected now, not the one from the old incident");
        assertEquals(1, incidentsForPayment(), "the old incident was re-used; no duplicate incident was created");
    }

    @Test
    void aHealthyServiceKeepsItsOldIncidentUntilItFailsAgain() throws Exception {
        long staleId = staleFailedIncident(paymentPrimary(), "FIXED_RETRY", 0);
        Thread.sleep(2000);                                          // plenty of monitor polls, no failure
        Incident i = incidentRepo.findById(staleId).orElseThrow();
        assertEquals(IncidentState.FAILED, i.state);
        assertEquals(0, i.epoch);
    }

    @Test
    void autoReopenIsBoundedByTheMaximumEpoch() throws Exception {
        ServiceNode node = paymentPrimary();
        long staleId = staleFailedIncident(node, "FIXED_RETRY", 3);   // already at the default max-epoch of 3
        faults.inject(FaultType.CRASH, node.id, null, null, null);
        Thread.sleep(3000);
        Incident i = incidentRepo.findById(staleId).orElseThrow();
        assertEquals(IncidentState.FAILED, i.state, "no further automatic attempts after the limit");
        assertEquals(1, incidentsForPayment());
    }
}
