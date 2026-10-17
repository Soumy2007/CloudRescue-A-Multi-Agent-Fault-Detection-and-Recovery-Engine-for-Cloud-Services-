package com.cloudrescue.simulator;

import com.cloudrescue.fault.FaultType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory simulated cloud. Holds the injected fault state per node and region, answers health probes,
 * performs restarts, and keeps an "effect log" keyed by operation key, which plays the role of a
 * downstream system the idempotency module can reconcile against.
 */
@Service
public class SimulatorService {

    private static final class NodeState {
        volatile boolean crashed;
        volatile int extraLatencyMs;
        volatile double errorRate;
        volatile double restartFailureProb;
        volatile double ackLossProb;
    }

    private final Map<Long, NodeState> states = new ConcurrentHashMap<>();
    private final Set<String> regionsDown = ConcurrentHashMap.newKeySet();
    private final Map<String, String> effects = new ConcurrentHashMap<>();
    private final Map<String, Integer> effectCounts = new ConcurrentHashMap<>();
    private final Random rng;

    public SimulatorService(@Value("${cloudrescue.simulation.seed:42}") long seed) {
        this.rng = new Random(seed);
    }

    private NodeState st(long id) {
        return states.computeIfAbsent(id, k -> new NodeState());
    }

    private synchronized double nextDouble() { return rng.nextDouble(); }

    private synchronized int nextInt(int bound) { return rng.nextInt(bound); }

    public HealthResult probe(ServiceNode node, int timeoutMs) {
        NodeState s = st(node.id);
        if (regionsDown.contains(node.region) || s.crashed) {
            return new HealthResult(false, timeoutMs, 503);
        }
        int latency = 20 + s.extraLatencyMs + nextInt(20);
        if (latency > timeoutMs) return new HealthResult(false, latency, 504);
        if (s.errorRate > 0 && nextDouble() < s.errorRate) return new HealthResult(false, latency, 500);
        return new HealthResult(true, latency, 200);
    }

    public void apply(FaultType type, Long nodeId, String region, double severity) {
        switch (type) {
            case CRASH -> st(nodeId).crashed = true;
            case LATENCY -> st(nodeId).extraLatencyMs = (int) severity;
            case ERROR_RATE -> st(nodeId).errorRate = severity;
            case RESTART_FAILURE -> st(nodeId).restartFailureProb = severity;
            case ACK_LOSS -> st(nodeId).ackLossProb = severity;
            case REGION_OUTAGE -> regionsDown.add(region);
        }
    }

    public void clear(FaultType type, Long nodeId, String region) {
        switch (type) {
            case CRASH -> st(nodeId).crashed = false;
            case LATENCY -> st(nodeId).extraLatencyMs = 0;
            case ERROR_RATE -> st(nodeId).errorRate = 0;
            case RESTART_FAILURE -> st(nodeId).restartFailureProb = 0;
            case ACK_LOSS -> st(nodeId).ackLossProb = 0;
            case REGION_OUTAGE -> regionsDown.remove(region);
        }
    }

    /** Restart clears process-level faults (crash, latency, errors) but cannot fix a region outage. */
    public ActionResult restart(ServiceNode node, String effectKey) {
        NodeState s = st(node.id);
        if (regionsDown.contains(node.region)) {
            return ActionResult.fail("region " + node.region + " unreachable");
        }
        if (nextDouble() < s.restartFailureProb) {
            return ActionResult.fail("restart of " + node.serviceName + "@" + node.region + " failed");
        }
        s.crashed = false;
        s.extraLatencyMs = 0;
        s.errorRate = 0;
        String msg = "restarted " + node.serviceName + "@" + node.region;
        recordEffect(effectKey, "OK:" + msg);
        maybeLoseAck(node.id, effectKey);
        return ActionResult.ok(msg);
    }

    /** Applies the configured acknowledgement-loss fault AFTER the effect happened. */
    public void maybeLoseAck(long nodeId, String key) {
        if (nextDouble() < st(nodeId).ackLossProb) throw new AckLostException(key);
    }

    public void recordEffect(String key, String storedResult) {
        effects.put(key, storedResult);
        effectCounts.merge(key, 1, Integer::sum);
    }

    /** Reconciliation hook: did the side effect for this operation key already happen? */
    public Optional<String> effectResult(String key) {
        return Optional.ofNullable(effects.get(key));
    }

    /** Number of effects applied more than once for the same operation key (should stay 0). */
    public int duplicateEffects() {
        return effectCounts.values().stream().mapToInt(c -> Math.max(0, c - 1)).sum();
    }
}
