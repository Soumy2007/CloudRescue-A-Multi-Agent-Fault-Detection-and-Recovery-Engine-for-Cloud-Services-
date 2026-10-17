package com.cloudrescue.fault;

import com.cloudrescue.common.Errors.BadRequestException;
import com.cloudrescue.common.Errors.ForbiddenException;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import com.cloudrescue.simulator.SimulatorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class FaultService {
    private static final Set<FaultType> PROCESS_FAULTS = EnumSet.of(FaultType.CRASH, FaultType.LATENCY, FaultType.ERROR_RATE);
    private static final Set<FaultType> OUTAGE_FAULTS = EnumSet.of(FaultType.CRASH, FaultType.LATENCY, FaultType.ERROR_RATE, FaultType.REGION_OUTAGE);

    private final FaultInjectionRepository repo;
    private final ServiceNodeRepository nodes;
    private final SimulatorService simulator;
    private final Clock clock;
    private final boolean enabled;
    private final boolean workersEnabled;

    public FaultService(FaultInjectionRepository repo, ServiceNodeRepository nodes, SimulatorService simulator, Clock clock,
                        @Value("${cloudrescue.fault-injection-enabled:true}") boolean enabled,
                        @Value("${cloudrescue.workers.enabled:true}") boolean workersEnabled) {
        this.repo = repo;
        this.nodes = nodes;
        this.simulator = simulator;
        this.clock = clock;
        this.enabled = enabled;
        this.workersEnabled = workersEnabled;
    }

    @Transactional
    public FaultInjection inject(FaultType type, Long nodeId, String region, Double severity, Integer durationSec) {
        if (!enabled) throw new ForbiddenException("Fault injection is disabled in this environment");
        FaultInjection f = new FaultInjection();
        f.faultType = type;
        if (type == FaultType.REGION_OUTAGE) {
            if (region == null || !(region.equals("A") || region.equals("B"))) {
                throw new BadRequestException("REGION_OUTAGE requires region 'A' or 'B'");
            }
            f.targetRegion = region;
        } else {
            if (nodeId == null) throw new BadRequestException(type + " requires nodeId");
            ServiceNode n = nodes.findById(nodeId).orElseThrow(() -> new BadRequestException("Unknown nodeId " + nodeId));
            f.targetNodeId = n.id;
            f.targetRegion = n.region;
        }
        f.severity = severity != null ? severity : defaultSeverity(type);
        if (f.severity < 0 || (type == FaultType.ERROR_RATE && f.severity > 1)) {
            throw new BadRequestException("Invalid severity " + f.severity);
        }
        Instant now = clock.instant();
        f.startedAt = now;
        f.scheduledEndAt = durationSec != null ? now.plusSeconds(durationSec) : null;
        repo.save(f);
        simulator.apply(type, f.targetNodeId, f.targetRegion, f.severity);
        return f;
    }

    private static double defaultSeverity(FaultType t) {
        return switch (t) {
            case LATENCY -> 2000;
            case ERROR_RATE -> 0.9;
            case RESTART_FAILURE -> 1.0;
            case ACK_LOSS -> 0.5;
            default -> 1.0;
        };
    }

    @Transactional
    public int reset() {
        List<FaultInjection> active = repo.findByEndedAtIsNull();
        active.forEach(f -> end(f, "RESET"));
        return active.size();
    }

    /** A restart fixes crash/latency/error faults on that node; their records are closed accordingly. */
    @Transactional
    public void endProcessFaultsOnNode(Long nodeId, String reason) {
        repo.findByEndedAtIsNull().stream()
                .filter(f -> nodeId.equals(f.targetNodeId) && PROCESS_FAULTS.contains(f.faultType))
                .forEach(f -> end(f, reason));
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void expire() {
        if (!workersEnabled) return;
        Instant now = clock.instant();
        repo.findByEndedAtIsNull().stream()
                .filter(f -> f.scheduledEndAt != null && !f.scheduledEndAt.isAfter(now))
                .forEach(f -> end(f, "EXPIRED"));
    }

    private void end(FaultInjection f, String reason) {
        f.endedAt = clock.instant();
        f.endReason = reason;
        simulator.clear(f.faultType, f.targetNodeId, f.targetRegion);
    }

    /** Re-applies still-active faults after a restart of the application. */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildSimulatorState() {
        repo.findByEndedAtIsNull().forEach(f -> simulator.apply(f.faultType, f.targetNodeId, f.targetRegion, f.severity));
    }

    @Transactional(readOnly = true)
    public Long findActiveFaultId(ServiceNode node) {
        return repo.findByEndedAtIsNull().stream()
                .filter(f -> OUTAGE_FAULTS.contains(f.faultType))
                .filter(f -> node.id.equals(f.targetNodeId)
                        || (f.faultType == FaultType.REGION_OUTAGE && node.region.equals(f.targetRegion)))
                .map(f -> f.id).max(Long::compare).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<FaultInjection> recent() {
        return repo.findTop30ByOrderByIdDesc();
    }
}
