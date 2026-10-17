package com.cloudrescue.api;

import com.cloudrescue.analytics.AnalyticsService;
import com.cloudrescue.api.Dtos.*;
import com.cloudrescue.experiment.ExperimentService;
import com.cloudrescue.experiment.SimulationEngine;
import com.cloudrescue.fault.FaultService;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.monitor.MonitorService;
import com.cloudrescue.monitor.NodeHealth;
import com.cloudrescue.policy.PolicyRegistry;
import com.cloudrescue.policy.PolicyRunRepository;
import com.cloudrescue.remediation.RemediationActionRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Thin HTTP layer: validate, delegate, map to DTOs. Business logic lives in the services. */
public final class Controllers {
    private Controllers() {}

    @RestController
    @RequestMapping("/api/services")
    public static class ServicesController {
        private final MonitorService monitor;
        public ServicesController(MonitorService monitor) { this.monitor = monitor; }

        @GetMapping
        public List<NodeHealth> services() { return monitor.snapshot(); }
    }

    @RestController
    @RequestMapping("/api/incidents")
    public static class IncidentController {
        private final IncidentService incidents;
        private final RemediationActionRepository actions;
        private final PolicyRunRepository decisions;

        public IncidentController(IncidentService incidents, RemediationActionRepository actions, PolicyRunRepository decisions) {
            this.incidents = incidents;
            this.actions = actions;
            this.decisions = decisions;
        }

        @GetMapping
        public PageDto<IncidentDto> list(@RequestParam(required = false) IncidentState state,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
            var p = incidents.page(state, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                    Sort.by(Sort.Direction.DESC, "id")));
            return new PageDto<>(p.map(IncidentDto::of).getContent(), p.getNumber(), p.getSize(), p.getTotalElements());
        }

        @GetMapping("/{id}")
        public IncidentDetail detail(@PathVariable long id) {
            var inc = incidents.get(id);
            return new IncidentDetail(IncidentDto.of(inc),
                    incidents.history(id).stream().map(TransitionDto::of).toList(),
                    actions.findByIncidentIdOrderBySeqNoAsc(id).stream().map(ActionDto::of).toList(),
                    decisions.findByIncidentIdOrderByIdAsc(id).stream().map(DecisionDto::of).toList());
        }

        /** Manual recovery path: operator re-opens a FAILED/ESCALATED incident, optionally forcing the next action. */
        @PostMapping("/{id}/reopen")
        public IncidentDto reopen(@PathVariable long id, @RequestBody(required = false) ReopenRequest req) {
            return IncidentDto.of(incidents.reopen(id, req == null ? null : req.action()));
        }
    }

    @RestController
    @RequestMapping("/api/faults")
    public static class FaultController {
        private final FaultService faults;
        public FaultController(FaultService faults) { this.faults = faults; }

        @GetMapping
        public List<FaultDto> recent() { return faults.recent().stream().map(FaultDto::of).toList(); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public FaultDto inject(@Valid @RequestBody FaultRequest r) {
            return FaultDto.of(faults.inject(r.type(), r.nodeId(), r.region(), r.severity(), r.durationSec()));
        }

        @PostMapping("/reset")
        public Map<String, Integer> reset() { return Map.of("ended", faults.reset()); }
    }

    @RestController
    @RequestMapping("/api/policies")
    public static class PolicyController {
        private final PolicyRegistry registry;
        public PolicyController(PolicyRegistry registry) { this.registry = registry; }

        @GetMapping
        public List<PolicyInfo> list() {
            String active = registry.active().name();
            return registry.all().stream().map(p -> new PolicyInfo(p.name(), p.description(), p.name().equals(active))).toList();
        }

        @PutMapping("/active")
        public List<PolicyInfo> setActive(@Valid @RequestBody SetPolicyRequest r) {
            registry.setActive(r.name());
            return list();
        }
    }

    @RestController
    @RequestMapping("/api/experiments")
    public static class ExperimentController {
        private final ExperimentService experiments;
        private final AnalyticsService analytics;

        public ExperimentController(ExperimentService experiments, AnalyticsService analytics) {
            this.experiments = experiments;
            this.analytics = analytics;
        }

        @GetMapping("/scenarios")
        public List<SimulationEngine.Scenario> scenarios() { return experiments.scenarios(); }

        @GetMapping
        public List<Map<String, Object>> batches() { return analytics.batches(); }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public AnalyticsService.ExperimentSummary run(@Valid @RequestBody(required = false) ExperimentRequest r) {
            ExperimentRequest req = r != null ? r : new ExperimentRequest(null, null, null, null, null);
            String batch = experiments.run(req.scenarios(), req.policies(),
                    req.trials() != null ? req.trials() : 30,
                    req.seed() != null ? req.seed() : 1000L,
                    req.idempotencyEnabled() == null || req.idempotencyEnabled());
            return analytics.summarize(batch);
        }

        @GetMapping("/{batchId}/summary")
        public AnalyticsService.ExperimentSummary summary(@PathVariable String batchId) { return analytics.summarize(batchId); }
    }

    @RestController
    @RequestMapping("/api/analytics")
    public static class AnalyticsController {
        private final AnalyticsService analytics;
        public AnalyticsController(AnalyticsService analytics) { this.analytics = analytics; }

        @GetMapping("/live")
        public AnalyticsService.LiveAnalytics live() { return analytics.live(); }

        @GetMapping("/metrics")
        public List<AnalyticsService.MetricDefinition> metrics() { return analytics.metricDefinitions(); }
    }
}
