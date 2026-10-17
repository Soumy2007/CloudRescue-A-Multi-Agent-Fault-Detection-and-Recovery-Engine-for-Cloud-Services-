package com.cloudrescue.monitor;

import com.cloudrescue.fault.FaultService;
import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.policy.PolicyRegistry;
import com.cloudrescue.simulator.HealthResult;
import com.cloudrescue.simulator.ServiceNode;
import com.cloudrescue.simulator.ServiceNodeRepository;
import com.cloudrescue.simulator.SimulatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls every node, keeps a rolling probe history, and opens an incident when the active node of a service
 * fails at least 3 of its last 5 probes. A duplicate incident for an unresolved failure is prevented by an
 * application check and, as a backstop, by a partial unique index in PostgreSQL.
 */
@Service
public class MonitorService {
    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);
    private static final int HISTORY = 60;
    private static final int DETECT_WINDOW = 5;
    private static final int DETECT_FAILURES = 3;

    private record Sample(boolean ok, int latencyMs, int status) {}

    private final ServiceNodeRepository nodes;
    private final SimulatorService simulator;
    private final IncidentService incidentService;
    private final IncidentRepository incidentRepo;
    private final FaultService faultService;
    private final PolicyRegistry policies;
    private final int probeTimeoutMs;
    private final boolean enabled;
    private final Map<Long, Deque<Sample>> history = new HashMap<>();

    public MonitorService(ServiceNodeRepository nodes, SimulatorService simulator, IncidentService incidentService,
                          IncidentRepository incidentRepo, FaultService faultService, PolicyRegistry policies,
                          @Value("${cloudrescue.monitor.probe-timeout-ms:1000}") int probeTimeoutMs,
                          @Value("${cloudrescue.workers.enabled:true}") boolean enabled) {
        this.nodes = nodes;
        this.simulator = simulator;
        this.incidentService = incidentService;
        this.incidentRepo = incidentRepo;
        this.faultService = faultService;
        this.policies = policies;
        this.probeTimeoutMs = probeTimeoutMs;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${cloudrescue.monitor.poll-interval-ms:1000}")
    public void poll() {
        if (!enabled) return;
        List<ServiceNode> all = nodes.findAll();
        synchronized (this) {
            for (ServiceNode n : all) {
                HealthResult r = simulator.probe(n, probeTimeoutMs);
                Deque<Sample> d = history.computeIfAbsent(n.id, k -> new ArrayDeque<>());
                d.addLast(new Sample(r.ok(), r.latencyMs(), r.status()));
                if (d.size() > HISTORY) d.removeFirst();
            }
        }
        for (ServiceNode n : all) {
            if (!n.active || failuresInWindow(n.id) < DETECT_FAILURES) continue;
            if (incidentRepo.existsByServiceNameAndStateNot(n.serviceName, IncidentState.RECOVERED)) continue;
            FailureType type = classify(n, all);
            try {
                incidentService.open(n, type, policies.active().name(), faultService.findActiveFaultId(n),
                        "Detected " + type + " on " + n.serviceName + "@" + n.region);
                log.info("Incident opened for {}@{} ({})", n.serviceName, n.region, type);
            } catch (DataIntegrityViolationException e) {
                log.debug("Incident for {} already open (unique index)", n.serviceName);
            }
        }
    }

    private synchronized int failuresInWindow(Long nodeId) {
        Deque<Sample> d = history.get(nodeId);
        if (d == null) return 0;
        List<Sample> list = new ArrayList<>(d);
        int from = Math.max(0, list.size() - DETECT_WINDOW);
        return (int) list.subList(from, list.size()).stream().filter(s -> !s.ok()).count();
    }

    /** Heuristic: several nodes in the same region failing together suggests a regional problem. */
    private FailureType classify(ServiceNode n, List<ServiceNode> all) {
        boolean peersFailing = all.stream().anyMatch(o -> !o.id.equals(n.id) && o.region.equals(n.region)
                && failuresInWindow(o.id) >= DETECT_FAILURES);
        if (peersFailing) return FailureType.REGION_SUSPECTED;
        Sample last = lastSample(n.id);
        if (last == null || last.status() == 503) return FailureType.UNAVAILABLE;
        return last.status() == 504 ? FailureType.HIGH_LATENCY : FailureType.HIGH_ERROR_RATE;
    }

    private synchronized Sample lastSample(Long nodeId) {
        Deque<Sample> d = history.get(nodeId);
        return d == null || d.isEmpty() ? null : d.peekLast();
    }

    /** After a verified recovery the old failures must not trigger a fresh incident. */
    public synchronized void clearHistory(String serviceName) {
        nodes.findByServiceName(serviceName).forEach(n -> history.remove(n.id));
    }

    public boolean isBackupHealthy(String serviceName) {
        return nodes.findFirstByServiceNameAndActiveFalse(serviceName).map(b -> {
            Sample s = lastSample(b.id);
            return s != null ? s.ok() : simulator.probe(b, probeTimeoutMs).ok();
        }).orElse(false);
    }

    public synchronized List<NodeHealth> snapshot() {
        List<NodeHealth> out = new ArrayList<>();
        for (ServiceNode n : nodes.findAll()) {
            Deque<Sample> d = history.getOrDefault(n.id, new ArrayDeque<>());
            List<Sample> list = new ArrayList<>(d);
            Sample last = list.isEmpty() ? null : list.get(list.size() - 1);
            Double avail = list.isEmpty() ? null : 100.0 * list.stream().filter(Sample::ok).count() / list.size();
            List<Boolean> recent = list.subList(Math.max(0, list.size() - 30), list.size()).stream().map(Sample::ok).toList();
            out.add(new NodeHealth(n.id, n.serviceName, n.region, n.role.name(), n.active,
                    last == null ? null : last.ok(), last == null ? null : last.latencyMs(),
                    last == null ? null : last.status(), avail, recent));
        }
        return out;
    }
}
