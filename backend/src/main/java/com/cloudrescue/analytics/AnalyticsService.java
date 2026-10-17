package com.cloudrescue.analytics;

import com.cloudrescue.experiment.ExperimentRun;
import com.cloudrescue.experiment.ExperimentRunRepository;
import com.cloudrescue.fault.FaultInjectionRepository;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.common.Errors.NotFoundException;
import com.cloudrescue.simulator.SimulatorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    /** mean/sd are null when n == 0 (metric undefined), never NaN, so the JSON stays valid. */
    public record Stat(Double mean, Double sd, int n) {}

    public record CellSummary(String scenario, String policy, int trials, double recoverySuccessRate,
                              double escalationRate, Stat detectionDelaySec, Stat recoveryTimeSec, Stat totalTimeSec,
                              Stat availability, Stat cost, double meanDuplicateOps, double meanRetries,
                              double meanRestarts, double meanFailovers, boolean idempotencyEnabled) {}

    public record ExperimentSummary(String batchId, int totalTrials, List<CellSummary> cells, List<CellSummary> overall) {}

    public record LivePolicyStat(String policy, int incidents, int recovered, Double meanRecoverySec, Double meanDetectionDelaySec) {}

    public record LiveAnalytics(Map<String, Long> incidentsByState, List<LivePolicyStat> perPolicy, int duplicateEffects) {}

    public record MetricDefinition(String name, String definition, String kind) {}

    private final ExperimentRunRepository runs;
    private final IncidentRepository incidents;
    private final FaultInjectionRepository faults;
    private final SimulatorService simulator;

    public AnalyticsService(ExperimentRunRepository runs, IncidentRepository incidents,
                            FaultInjectionRepository faults, SimulatorService simulator) {
        this.runs = runs;
        this.incidents = incidents;
        this.faults = faults;
        this.simulator = simulator;
    }

    static Stat stat(List<Double> xs) {
        int n = xs.size();
        if (n == 0) return new Stat(null, null, 0);
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = n < 2 ? 0 : Math.sqrt(xs.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (n - 1));
        return new Stat(mean, sd, n);
    }

    private static List<Double> nonNull(List<ExperimentRun> rs, Function<ExperimentRun, Double> f) {
        return rs.stream().map(f).filter(Objects::nonNull).toList();
    }

    static CellSummary cell(String scenario, String policy, List<ExperimentRun> rs) {
        int n = rs.size();
        return new CellSummary(scenario, policy, n,
                rs.stream().filter(r -> r.recovered).count() / (double) n,
                rs.stream().filter(r -> r.escalated).count() / (double) n,
                stat(nonNull(rs, r -> r.detectionDelaySec)),
                stat(nonNull(rs, r -> r.recoveryTimeSec)),
                stat(nonNull(rs, r -> r.totalTimeSec)),
                stat(rs.stream().map(r -> r.availability).toList()),
                stat(rs.stream().map(r -> r.cost).toList()),
                rs.stream().mapToInt(r -> r.duplicateOps).average().orElse(0),
                rs.stream().mapToInt(r -> r.retries).average().orElse(0),
                rs.stream().mapToInt(r -> r.restarts).average().orElse(0),
                rs.stream().mapToInt(r -> r.failovers).average().orElse(0),
                rs.get(0).idempotencyEnabled);
    }

    @Transactional(readOnly = true)
    public ExperimentSummary summarize(String batchId) {
        List<ExperimentRun> all = runs.findByBatchId(batchId);
        if (all.isEmpty()) throw new NotFoundException("Experiment batch " + batchId + " not found");
        Map<String, List<ExperimentRun>> byCell = all.stream().collect(
                Collectors.groupingBy(r -> r.scenario + "|" + r.policyName, LinkedHashMap::new, Collectors.toList()));
        List<CellSummary> cells = byCell.values().stream()
                .map(rs -> cell(rs.get(0).scenario, rs.get(0).policyName, rs)).toList();
        Map<String, List<ExperimentRun>> byPolicy = all.stream().collect(
                Collectors.groupingBy(r -> r.policyName, LinkedHashMap::new, Collectors.toList()));
        List<CellSummary> overall = byPolicy.entrySet().stream().map(e -> cell("ALL_SCENARIOS", e.getKey(), e.getValue())).toList();
        return new ExperimentSummary(batchId, all.size(), cells, overall);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> batches() {
        return runs.listBatches().stream()
                .map(o -> Map.of("batchId", o[0], "createdAt", o[1], "trials", o[2])).toList();
    }

    @Transactional(readOnly = true)
    public LiveAnalytics live() {
        List<Incident> all = incidents.findAll();
        Map<String, Long> byState = new LinkedHashMap<>();
        for (IncidentState s : IncidentState.values()) byState.put(s.name(), 0L);
        all.forEach(i -> byState.merge(i.state.name(), 1L, Long::sum));
        List<LivePolicyStat> perPolicy = all.stream().collect(Collectors.groupingBy(i -> i.policyName)).entrySet().stream()
                .map(e -> {
                    List<Incident> is = e.getValue();
                    List<Double> rec = is.stream().filter(i -> i.recoveredAt != null && i.epoch == 0)
                            .map(i -> Duration.between(i.detectedAt, i.recoveredAt).toMillis() / 1000.0).toList();
                    List<Double> det = is.stream().filter(i -> i.faultInjectionId != null)
                            .map(i -> faults.findById(i.faultInjectionId)
                                    .map(f -> Duration.between(f.startedAt, i.detectedAt).toMillis() / 1000.0).orElse(null))
                            .filter(Objects::nonNull).toList();
                    return new LivePolicyStat(e.getKey(), is.size(), rec.size(), stat(rec).mean(), stat(det).mean());
                }).toList();
        return new LiveAnalytics(byState, perPolicy, simulator.duplicateEffects());
    }

    public List<MetricDefinition> metricDefinitions() {
        return List.of(
                new MetricDefinition("Detection delay", "Seconds from fault start to incident creation (3 failed probes of the last 5).", "measured"),
                new MetricDefinition("Recovery time", "Seconds from incident detection to verified recovery. Only trials that recovered are included.", "measured"),
                new MetricDefinition("Total time", "Seconds from fault start to verified recovery (detection delay + recovery time).", "measured"),
                new MetricDefinition("Availability", "Fraction of 1-second ticks in the observation window in which the service answered a probe successfully.", "measured"),
                new MetricDefinition("Recovery success rate", "Share of trials that reached verified recovery within the observation window.", "measured"),
                new MetricDefinition("Duplicate operations", "Remediation side effects applied more than once for the same logical operation.", "measured"),
                new MetricDefinition("Retries / restarts / failovers", "Counts of executed actions per trial (escalation depth).", "measured"),
                new MetricDefinition("Cost", "Retry 0.1 + restart 1.0 + failover 5.0 abstract units. These weights are ASSUMPTIONS, not measurements.", "assumption"));
    }
}
