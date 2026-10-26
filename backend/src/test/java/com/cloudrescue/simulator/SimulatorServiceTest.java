package com.cloudrescue.simulator;

import com.cloudrescue.fault.FaultType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests of the simulated cloud: what a restart can and cannot repair. */
class SimulatorServiceTest {
    private static ServiceNode node(long id, String region) {
        ServiceNode n = new ServiceNode();
        n.id = id;
        n.serviceName = "payment-service";
        n.region = region;
        n.role = NodeRole.PRIMARY;
        n.active = true;
        return n;
    }

    @Test
    void restartCannotRepairARegionOutage() {
        SimulatorService sim = new SimulatorService(1);
        ServiceNode a = node(1, "A");
        sim.apply(FaultType.REGION_OUTAGE, null, "A", 1.0);
        sim.apply(FaultType.CRASH, 1L, "A", 1.0);

        ActionResult r = sim.restart(a, "k-region");

        assertFalse(r.success());
        assertFalse(sim.probe(a, 1000).ok(), "node must still be unreachable");
        assertTrue(sim.effectResult("k-region").isEmpty(), "no restart effect may be recorded");
        assertEquals(0, sim.effectCount("k-region"));
    }

    @Test
    void restartRepairsAProcessCrashWhenRegionIsUp() {
        SimulatorService sim = new SimulatorService(1);
        ServiceNode a = node(1, "A");
        sim.apply(FaultType.CRASH, 1L, "A", 1.0);
        assertFalse(sim.probe(a, 1000).ok());

        assertTrue(sim.restart(a, "k-crash").success());

        assertTrue(sim.probe(a, 1000).ok());
        assertEquals(1, sim.effectCount("k-crash"));
    }

    @Test
    void failedRestartLeavesTheNodeDown() {
        SimulatorService sim = new SimulatorService(1);
        ServiceNode a = node(1, "A");
        sim.apply(FaultType.CRASH, 1L, "A", 1.0);
        sim.apply(FaultType.RESTART_FAILURE, 1L, "A", 1.0);

        assertFalse(sim.restart(a, "k-fail").success());
        assertFalse(sim.probe(a, 1000).ok());
        assertEquals(0, sim.effectCount("k-fail"));
    }

    @Test
    void lostAckHappensAfterTheEffectAndIsNotRepeatedByReconciliation() {
        SimulatorService sim = new SimulatorService(1);
        ServiceNode a = node(1, "A");
        sim.apply(FaultType.CRASH, 1L, "A", 1.0);
        sim.apply(FaultType.ACK_LOSS, 1L, "A", 1.0);

        assertThrows(AckLostException.class, () -> sim.restart(a, "k-ack"));

        assertEquals(1, sim.effectCount("k-ack"), "the effect happened exactly once");
        assertTrue(sim.effectResult("k-ack").isPresent(), "reconciliation can find it");
        assertEquals(0, sim.duplicateEffects());
    }

    @Test
    void clearingTheRegionOutageRestoresProbes() {
        SimulatorService sim = new SimulatorService(1);
        ServiceNode a = node(1, "A");
        sim.apply(FaultType.REGION_OUTAGE, null, "A", 1.0);
        assertFalse(sim.probe(a, 1000).ok());
        sim.clear(FaultType.REGION_OUTAGE, null, "A");
        assertTrue(sim.probe(a, 1000).ok());
    }
}
