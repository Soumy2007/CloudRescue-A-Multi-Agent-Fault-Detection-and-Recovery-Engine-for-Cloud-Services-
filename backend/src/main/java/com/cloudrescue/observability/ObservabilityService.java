package com.cloudrescue.observability;

import com.cloudrescue.api.Dtos.IncidentDto;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.monitor.MonitorService;
import com.cloudrescue.monitor.NodeHealth;
import com.cloudrescue.remediation.ActionStatus;
import com.cloudrescue.remediation.RemediationActionRepository;
import com.cloudrescue.remediation.VerificationOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only summary for the dashboard, built from the durable PostgreSQL records (incidents, remediation actions)
 * and from the in-memory availability tracker. Nothing here is hard-coded or estimated.
 */
@Service
public class ObservabilityService {

    public record ServiceAvailability(String service, String servingRegion, Boolean healthyNow,
                                      Double availabilityRatio, int samples) {}

    public record Meta(String measurement, int availabilityWindowSamples, List<String> notes) {}

    public record Summary(Instant generatedAt, long totalIncidents, long activeIncidents,
                          Map<String, Long> incidentsByState, long recoveredIncidents, long failedIncidents,
                          long escalatedIncidents, Double recoverySuccessRate,
                          RecoveryStats.DurationSummary recoveryDuration,
                          Map<String, Long> verificationOutcomes, Map<String, Long> actionResults,
                          List<ServiceAvailability> services, List<IncidentDto> recentIncidents, Meta meta) {}

    private final IncidentRepository incidents;
    private final RemediationActionRepository actions;
    private final MonitorService monitor;
    private final AvailabilityTracker availability;
    private final Clock clock;

    public ObservabilityService(IncidentRepository incidents, RemediationActionRepository actions,
                                MonitorService monitor, AvailabilityTracker availability, Clock clock) {
        this.incidents = incidents;
        this.actions = actions;
        this.monitor = monitor;
        this.availability = availability;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        List<Incident> all = incidents.findAll();

        Map<String, Long> byState = new LinkedHashMap<>();
        for (IncidentState s : IncidentState.values()) byState.put(s.name(), 0L);
        all.forEach(i -> byState.merge(i.state.name(), 1L, Long::sum));
        long recovered = byState.get(IncidentState.RECOVERED.name());
        long failed = byState.get(IncidentState.FAILED.name());
        long escalated = byState.get(IncidentState.ESCALATED.name());

        // Only automatic recoveries: an operator re-open includes the time a human waited.
        List<Double> durations = all.stream()
                .filter(i -> i.recoveredAt != null && i.epoch == 0)
                .map(i -> Duration.between(i.detectedAt, i.recoveredAt).toMillis() / 1000.0)
                .toList();

        Map<String, Long> verification = new LinkedHashMap<>();
        for (VerificationOutcome o : VerificationOutcome.values()) verification.put(o.name(), actions.countByOutcome(o));

        Map<String, Long> results = new LinkedHashMap<>();
        results.put("SUCCEEDED", actions.countByStatus(ActionStatus.SUCCEEDED));
        results.put("FAILED", actions.countByStatus(ActionStatus.FAILED));

        List<ServiceAvailability> services = monitor.snapshot().stream()
                .filter(NodeHealth::active)
                .sorted(Comparator.comparing(NodeHealth::serviceName))
                .map(n -> {
                    int samples = availability.sampleCount(n.serviceName());
                    return new ServiceAvailability(n.serviceName(), n.region(), n.healthy(),
                            samples == 0 ? null : availability.ratio(n.serviceName()), samples);
                }).toList();

        List<IncidentDto> recent = all.stream()
                .sorted(Comparator.comparing((Incident i) -> i.id).reversed())
                .limit(10).map(IncidentDto::of).toList();

        return new Summary(clock.instant(), all.size(), all.size() - recovered, byState, recovered, failed, escalated,
                RecoveryStats.successRate(recovered, failed, escalated), RecoveryStats.summarize(durations),
                verification, results, services, recent,
                new Meta("simulated", availability.window(), List.of(
                        "All services, regions and failures are simulated; nothing here is real infrastructure monitoring.",
                        "Availability = share of the last " + availability.window()
                                + " monitor polls in which the node serving traffic answered its health probe.",
                        "Recovery duration covers automatic recoveries only (detection to verified recovery).",
                        "Success rate = recovered / (recovered + failed + escalated); unfinished incidents are excluded.")));
    }
}
