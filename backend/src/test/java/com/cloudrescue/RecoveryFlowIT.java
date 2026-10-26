package com.cloudrescue;

import com.cloudrescue.fault.FaultService;
import com.cloudrescue.fault.FaultType;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.policy.PolicyRegistry;
import com.cloudrescue.remediation.RemediationAction;
import com.cloudrescue.remediation.RemediationActionRepository;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import com.cloudrescue.simulator.SimulatorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: inject a fault, let the monitor detect it and the worker recover it, inspect PostgreSQL state. */
@SpringBootTest(properties = {
        "cloudrescue.workers.enabled=true",
        "cloudrescue.monitor.poll-interval-ms=200",
        "cloudrescue.recovery.tick-ms=200"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecoveryFlowIT extends PostgresIT {
    @Autowired FaultService faults;
    @Autowired PolicyRegistry policies;
    @Autowired IncidentRepository incidents;
    @Autowired RemediationActionRepository actions;
    @Autowired ServiceNodeRepository nodes;
    @Autowired SimulatorService simulator;

    @AfterEach
    void resetFaults() {
        faults.reset();
    }

    private ServiceNode primary(String service) {
        return nodes.findByServiceName(service).stream().filter(n -> n.region.equals("A")).findFirst().orElseThrow();
    }

    private void awaitRecovered(String service) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<Incident> list = incidents.findAll().stream().filter(i -> i.serviceName.equals(service)).toList();
            assertEquals(1, list.size(), "exactly one incident, no duplicates");
            assertEquals(IncidentState.RECOVERED, list.get(0).state);
        });
    }

    @Test
    void crashIsDetectedAndRecoveredByRestart() {
        policies.setActive("COST_AWARE");
        faults.inject(FaultType.CRASH, primary("payment-service").id, null, null, null);
        awaitRecovered("payment-service");
        Incident i = incidents.findAll().get(0);
        List<RemediationAction> as = actions.findByIncidentIdOrderBySeqNoAsc(i.id);
        assertTrue(as.stream().anyMatch(a -> a.actionType.name().equals("RESTART") && Boolean.TRUE.equals(a.verifiedHealthy)));
        assertEquals(0, simulator.duplicateEffects());
    }

    @Test
    void regionOutageTriggersFailoverToRegionB() {
        policies.setActive("COST_AWARE");
        faults.inject(FaultType.REGION_OUTAGE, null, "A", null, null);
        for (String s : List.of("payment-service", "order-service", "auth-service")) awaitRecovered(s);
        for (String s : List.of("payment-service", "order-service", "auth-service")) {
            assertEquals("B", nodes.findByServiceNameAndActiveTrue(s).orElseThrow().region);
        }
    }

    @Test
    void lostAcknowledgementDoesNotCauseDuplicateRestart() {
        policies.setActive("COST_AWARE");
        ServiceNode p = primary("order-service");
        faults.inject(FaultType.ACK_LOSS, p.id, null, 1.0, null);
        faults.inject(FaultType.CRASH, p.id, null, null, null);
        awaitRecovered("order-service");
        assertEquals(0, simulator.duplicateEffects(), "reconciliation must prevent a second restart");
        Incident i = incidents.findAll().get(0);
        assertTrue(actions.findByIncidentIdOrderBySeqNoAsc(i.id).stream()
                .anyMatch(a -> a.resultMessage != null && a.resultMessage.contains("replayed")));
    }
}
