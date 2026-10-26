package com.cloudrescue.remediation;

import com.cloudrescue.fault.FaultService;
import com.cloudrescue.idempotency.IdempotencyService;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.simulator.AckLostException;
import com.cloudrescue.simulator.ActionResult;
import com.cloudrescue.simulator.HealthResult;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import com.cloudrescue.simulator.SimulatorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Executes retry / restart / failover and verifies the result. All side effects go through the idempotency
 * module. Decision logic lives in the policies; this class never chooses WHAT to do, only does it and checks it.
 */
@Service
public class RemediationService {
    private static final int MAX_ATTEMPTS = 3;
    private static final int VERIFY_PROBES = 3;

    public record ExecutionResult(boolean actionOk, String message, int attempts) {}

    public record Verification(VerificationOutcome outcome, boolean observedHealthy, Long observedActiveNodeId) {}

    private final ServiceNodeRepository nodes;
    private final SimulatorService simulator;
    private final IdempotencyService idempotency;
    private final FaultService faults;
    private final TransactionTemplate tx;
    private final int probeTimeoutMs;

    public RemediationService(ServiceNodeRepository nodes, SimulatorService simulator, IdempotencyService idempotency,
                              FaultService faults, PlatformTransactionManager txManager,
                              @Value("${cloudrescue.monitor.probe-timeout-ms:1000}") int probeTimeoutMs) {
        this.nodes = nodes;
        this.simulator = simulator;
        this.idempotency = idempotency;
        this.faults = faults;
        this.tx = new TransactionTemplate(txManager);
        this.probeTimeoutMs = probeTimeoutMs;
    }

    /** Stable key for one logical operation: the same action row always maps to the same key. */
    public static String keyFor(long incidentId, int seqNo) {
        return "inc-" + incidentId + "-act-" + seqNo;
    }

    /**
     * Fixes which node the action targets and which node must be serving afterwards. Runs once, before the first
     * execution; a resumed or replayed action keeps its stored targets, so the idempotency request hash is stable.
     */
    public void prepare(Incident inc, RemediationAction a) {
        if (a.targetNodeId != null) return;
        ServiceNode active = nodes.findByServiceNameAndActiveTrue(inc.serviceName).orElseThrow();
        a.targetNodeId = active.id;
        a.expectedActiveNodeId = active.id;
        if (a.actionType == ActionType.FAILOVER) {
            nodes.findFirstByServiceNameAndActiveFalse(inc.serviceName).ifPresent(b -> a.expectedActiveNodeId = b.id);
        }
    }

    public ExecutionResult execute(Incident inc, RemediationAction action) {
        if (action.targetNodeId == null) {
            throw new IllegalStateException("prepare() must run before execute()");
        }
        String hash = sha256(action.actionType + "|" + inc.id + "|" + inc.serviceName + "|" + action.seqNo
                + "|" + action.targetNodeId + "|" + action.expectedActiveNodeId);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var outcome = idempotency.execute(action.idempotencyKey, hash,
                        () -> perform(action, action.idempotencyKey).toStored(),
                        simulator::effectResult);
                ActionResult r = ActionResult.fromStored(outcome.result());
                if (r.success() && action.actionType == ActionType.RESTART) {
                    faults.endProcessFaultsOnNode(action.targetNodeId, "RESTART");
                }
                return new ExecutionResult(r.success(),
                        r.message() + (outcome.replayed() ? " (replayed stored result)" : ""), attempt);
            } catch (AckLostException e) {
                // Outcome unknown: retry the SAME logical operation. The idempotency layer reconciles
                // instead of re-executing, so the side effect is not applied twice.
            }
        }
        return new ExecutionResult(false, "Outcome still uncertain after " + MAX_ATTEMPTS + " attempts", MAX_ATTEMPTS);
    }

    private ActionResult perform(RemediationAction a, String key) {
        ServiceNode target = nodes.findById(a.targetNodeId).orElseThrow();
        return switch (a.actionType) {
            case RETRY -> retry(target);
            case RESTART -> restart(target, key);
            case FAILOVER -> failover(a, target, key);
            default -> ActionResult.fail("Not an executable action: " + a.actionType);
        };
    }

    private static String label(ServiceNode n) {
        return n.serviceName + "@" + n.region;
    }

    private ActionResult retry(ServiceNode target) {
        if (!target.active) {
            return ActionResult.fail("precondition failed: " + label(target) + " is no longer serving traffic");
        }
        HealthResult h = simulator.probe(target, probeTimeoutMs);
        return h.ok() ? ActionResult.ok("health check passed (" + h.latencyMs() + "ms)")
                : ActionResult.fail("health check failed (HTTP " + h.status() + ")");
    }

    /** A process restart can only fix process-level faults; the simulator refuses it while the region is down. */
    private ActionResult restart(ServiceNode target, String key) {
        if (!target.active) {
            return ActionResult.fail("precondition failed: " + label(target) + " is no longer serving traffic");
        }
        return simulator.restart(target, key);
    }

    private ActionResult failover(RemediationAction a, ServiceNode source, String key) {
        ServiceNode backup = nodes.findById(a.expectedActiveNodeId).orElseThrow();
        if (backup.id.equals(source.id)) {
            return ActionResult.fail("no backup node exists");
        }
        if (backup.active && !source.active) {
            return ActionResult.ok("traffic already on " + label(backup) + " (idempotent no-op)");
        }
        if (!source.active) {
            return ActionResult.fail("precondition failed: " + label(source) + " is no longer serving traffic");
        }
        HealthResult h = simulator.probe(backup, probeTimeoutMs);
        if (!h.ok()) {
            return ActionResult.fail("backup in region " + backup.region + " is unhealthy; failover rejected");
        }
        Boolean switched = tx.execute(s -> {
            ServiceNode cur = nodes.findById(source.id).orElseThrow();
            ServiceNode bak = nodes.findById(backup.id).orElseThrow();
            if (!cur.active || bak.active) return false;      // changed concurrently: do nothing
            cur.active = false;
            nodes.saveAndFlush(cur);                          // flush order: never two active nodes at once
            bak.active = true;
            nodes.saveAndFlush(bak);
            return true;
        });
        if (!Boolean.TRUE.equals(switched)) {
            return ActionResult.fail("precondition failed: node roles changed concurrently");
        }
        String msg = "traffic for " + source.serviceName + " moved to region " + backup.region + " (simulated)";
        simulator.recordEffect(key, "OK:" + msg);
        simulator.maybeLoseAck(source.id, key);
        return ActionResult.ok(msg);
    }

    /**
     * Verifies the EXPECTED post-state, not merely "something answers": the node that must be serving traffic is
     * the one serving, it is healthy over several probes, and the action itself succeeded.
     */
    public Verification verify(Incident inc, RemediationAction a, boolean actionOk) {
        ServiceNode active = nodes.findByServiceNameAndActiveTrue(inc.serviceName).orElse(null);
        boolean healthy = false;
        if (active != null) {
            healthy = true;
            for (int i = 0; i < VERIFY_PROBES; i++) {
                if (!simulator.probe(active, probeTimeoutMs).ok()) {
                    healthy = false;
                    break;
                }
            }
        }
        Long observed = active == null ? null : active.id;
        return new Verification(
                RecoveryRules.evaluate(new RecoveryRules.Observation(actionOk, a.expectedActiveNodeId, observed, healthy)),
                healthy, observed);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
