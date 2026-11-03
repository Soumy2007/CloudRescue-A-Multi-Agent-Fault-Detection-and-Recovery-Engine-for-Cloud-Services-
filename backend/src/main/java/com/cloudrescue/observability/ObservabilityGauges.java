package com.cloudrescue.observability;

import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.simulator.ServiceNodeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.TreeSet;

/**
 * Registers the gauges, which are read on every scrape:
 *  - cloudrescue.active.incidents: unresolved incidents (every state except RECOVERED), straight from PostgreSQL.
 *  - cloudrescue.service.availability.ratio{service}: see {@link AvailabilityTracker}. NaN until the first sample.
 * A gauge whose function throws is reported as NaN by Micrometer, so a database hiccup cannot break the scrape.
 */
@Component
public class ObservabilityGauges {
    private final MeterRegistry registry;
    private final IncidentRepository incidents;
    private final ServiceNodeRepository nodes;
    private final AvailabilityTracker availability;

    public ObservabilityGauges(MeterRegistry registry, IncidentRepository incidents, ServiceNodeRepository nodes,
                               AvailabilityTracker availability) {
        this.registry = registry;
        this.incidents = incidents;
        this.nodes = nodes;
        this.availability = availability;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        Gauge.builder("cloudrescue.active.incidents", incidents, r -> r.countByStateNot(IncidentState.RECOVERED))
                .description("Unresolved incidents (every state except RECOVERED)")
                .register(registry);

        for (String service : new TreeSet<>(nodes.findAll().stream().map(n -> n.serviceName).toList())) {
            Gauge.builder("cloudrescue.service.availability.ratio", availability, t -> t.ratio(service))
                    .description("Share of recent monitor polls in which the serving node answered (simulated)")
                    .tag("service", service)
                    .register(registry);
        }
    }
}
