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
import java.util.Optional;

/** Executes retry / restart / failover. All side effects go through the idempotency module. */
@Service
public class RemediationService {
    private static final int MAX_ATTEMPTS = 3;
    private static final int VERIFY_PROBES = 3;

    public record ExecutionResult(boolean actionOk, String message, int attempts) {}

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

    public ExecutionResult execute(Incident inc, RemediationAction action) {
        String hash = sha256(action.actionType + "|" + inc.id + "|" + inc.serviceName + "|" + action.seqNo);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var outcome = idempotency.execute(action.idempotencyKey, hash,
                        () -> perform(action.actionType, inc, action.idempotencyKey).toStored(),
                        simulator::effectResult);
                ActionResult r = ActionResult.fromStored(outcome.result());
                if (r.success() && action.actionType == ActionType.RESTART) {
                    nodes.findByServiceNameAndActiveTrue(inc.serviceName)
                            .ifPresent(n -> faults.endProcessFaultsOnNode(n.id, "RESTART"));
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

    private ActionResult perform(ActionType type, Incident inc, String key) {
        ServiceNode active = nodes.findByServiceNameAndActiveTrue(inc.serviceName).orElseThrow();
        return switch (type) {
            case RETRY -> {
                HealthResult h = simulator.probe(active, probeTimeoutMs);
                yield h.ok() ? ActionResult.ok("health check passed (" + h.latencyMs() + "ms)")
                        : ActionResult.fail("health check failed (HTTP " + h.status() + ")");
            }
            case RESTART -> simulator.restart(active, key);
            case FAILOVER -> failover(active, key);
            default -> ActionResult.fail("Not an executable action: " + type);
        };
    }

    private ActionResult failover(ServiceNode current, String key) {
        Optional<ServiceNode> backup = nodes.findFirstByServiceNameAndActiveFalse(current.serviceName);
        if (backup.isEmpty()) return ActionResult.fail("no backup node exists");
        ServiceNode b = backup.get();
        HealthResult h = simulator.probe(b, probeTimeoutMs);
        if (!h.ok()) return ActionResult.fail("backup in region " + b.region + " is unhealthy; failover rejected");
        tx.executeWithoutResult(s -> {
            ServiceNode cur = nodes.findById(current.id).orElseThrow();
            ServiceNode bak = nodes.findById(b.id).orElseThrow();
            cur.active = false;
            bak.active = true;
            nodes.save(cur);
            nodes.save(bak);
        });
        String msg = "traffic for " + current.serviceName + " moved to region " + b.region + " (simulated)";
        simulator.recordEffect(key, "OK:" + msg);
        simulator.maybeLoseAck(current.id, key);
        return ActionResult.ok(msg);
    }

    /** Recovery verification: several consecutive healthy probes of the node now serving traffic. */
    public boolean verify(String serviceName) {
        ServiceNode active = nodes.findByServiceNameAndActiveTrue(serviceName).orElse(null);
        if (active == null) return false;
        for (int i = 0; i < VERIFY_PROBES; i++) {
            if (!simulator.probe(active, probeTimeoutMs).ok()) return false;
        }
        return true;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
