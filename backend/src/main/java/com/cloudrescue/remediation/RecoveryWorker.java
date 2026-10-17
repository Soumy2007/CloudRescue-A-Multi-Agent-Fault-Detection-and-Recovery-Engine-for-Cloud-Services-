package com.cloudrescue.remediation;

import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.monitor.MonitorService;
import com.cloudrescue.common.Errors.ConflictException;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.PolicyContext;
import com.cloudrescue.policy.PolicyRegistry;
import com.cloudrescue.policy.PolicyRun;
import com.cloudrescue.policy.PolicyRunRepository;
import com.cloudrescue.policy.RecoveryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Drives open incidents through DETECTED -> ANALYZING -> REMEDIATING -> RECOVERED/FAILED/ESCALATED.
 * Decision logic lives in the policies; execution lives in RemediationService; this class only orchestrates.
 * State is durable: after a crash the next tick resumes from what PostgreSQL says.
 */
@Component
public class RecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(RecoveryWorker.class);
    private static final List<ActionStatus> EXECUTED = List.of(ActionStatus.SUCCEEDED, ActionStatus.FAILED);
    private static final List<ActionStatus> OPEN = List.of(ActionStatus.PENDING, ActionStatus.RUNNING);

    private final IncidentRepository incidentRepo;
    private final IncidentService incidents;
    private final RemediationActionRepository actions;
    private final PolicyRunRepository policyRuns;
    private final PolicyRegistry policies;
    private final RemediationService remediation;
    private final MonitorService monitor;
    private final Clock clock;
    private final boolean enabled;

    public RecoveryWorker(IncidentRepository incidentRepo, IncidentService incidents, RemediationActionRepository actions,
                          PolicyRunRepository policyRuns, PolicyRegistry policies, RemediationService remediation,
                          MonitorService monitor, Clock clock,
                          @Value("${cloudrescue.workers.enabled:true}") boolean enabled) {
        this.incidentRepo = incidentRepo;
        this.incidents = incidents;
        this.actions = actions;
        this.policyRuns = policyRuns;
        this.policies = policies;
        this.remediation = remediation;
        this.monitor = monitor;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${cloudrescue.recovery.tick-ms:1000}")
    public void tick() {
        if (!enabled) return;
        for (Incident i : incidentRepo.findByStateIn(List.of(IncidentState.DETECTED, IncidentState.ANALYZING, IncidentState.REMEDIATING))) {
            try {
                step(i.id);
            } catch (OptimisticLockingFailureException | ConflictException e) {
                log.debug("Incident {} changed concurrently, skipping this tick: {}", i.id, e.getMessage());
            } catch (RuntimeException e) {
                log.error("Recovery step failed for incident {}", i.id, e);
            }
        }
    }

    void step(long id) {
        Incident inc = incidents.get(id);
        switch (inc.state) {
            case DETECTED -> incidents.transition(id, IncidentState.DETECTED, IncidentState.ANALYZING, "Analysis started");
            case ANALYZING -> analyze(inc);
            case REMEDIATING -> remediate(inc);
            default -> { }
        }
    }

    private void analyze(Incident inc) {
        RecoveryDecision d;
        if (inc.forcedAction != null) {
            d = RecoveryDecision.of(ActionType.valueOf(inc.forcedAction), 0, "Operator-requested action");
            incidents.consumeForcedAction(inc.id);
        } else {
            d = policies.get(inc.policyName).decide(new PolicyContext(
                    inc.failureType,
                    (int) count(inc, ActionType.RETRY), (int) count(inc, ActionType.RESTART), (int) count(inc, ActionType.FAILOVER),
                    Duration.between(inc.detectedAt, clock.instant()).toSeconds(),
                    monitor.isBackupHealthy(inc.serviceName)));
        }
        PolicyRun run = new PolicyRun();
        run.incidentId = inc.id;
        run.policyName = inc.policyName;
        run.failureType = inc.failureType;
        run.decisionAction = d.action();
        run.delayMs = d.delayMs();
        run.reason = d.reason();
        run.rejectedAlternatives = String.join("; ", d.rejected());
        run.decidedAt = clock.instant();
        policyRuns.save(run);

        switch (d.action()) {
            case ESCALATE -> incidents.transition(inc.id, IncidentState.ANALYZING, IncidentState.ESCALATED, d.reason());
            case GIVE_UP -> incidents.transition(inc.id, IncidentState.ANALYZING, IncidentState.FAILED, d.reason());
            default -> {
                RemediationAction a = new RemediationAction();
                a.incidentId = inc.id;
                a.seqNo = (int) actions.countByIncidentId(inc.id) + 1;
                a.epoch = inc.epoch;
                a.actionType = d.action();
                a.status = ActionStatus.PENDING;
                a.idempotencyKey = RemediationService.keyFor(inc.id, a.seqNo);
                a.scheduledAt = clock.instant().plusMillis(d.delayMs());
                actions.save(a);
                incidents.transition(inc.id, IncidentState.ANALYZING, IncidentState.REMEDIATING,
                        "Scheduled " + d.action() + ": " + d.reason());
            }
        }
    }

    private void remediate(Incident inc) {
        var open = actions.findFirstByIncidentIdAndStatusInOrderBySeqNoDesc(inc.id, OPEN);
        if (open.isEmpty()) {
            incidents.transition(inc.id, IncidentState.REMEDIATING, IncidentState.ANALYZING, "No pending action; re-analyzing");
            return;
        }
        RemediationAction a = open.get();
        Instant now = clock.instant();
        if (a.status == ActionStatus.PENDING && a.scheduledAt.isAfter(now)) return; // backoff delay not elapsed
        a.status = ActionStatus.RUNNING;
        if (a.startedAt == null) a.startedAt = now;
        actions.save(a);

        RemediationService.ExecutionResult r = remediation.execute(inc, a);
        boolean healthy = remediation.verify(inc.serviceName);

        a.status = r.actionOk() ? ActionStatus.SUCCEEDED : ActionStatus.FAILED;
        a.finishedAt = clock.instant();
        a.durationMs = Duration.between(a.startedAt, a.finishedAt).toMillis();
        a.attempts = r.attempts();
        a.resultMessage = r.message().length() > 480 ? r.message().substring(0, 480) : r.message();
        a.verifiedHealthy = healthy;
        actions.save(a);

        if (healthy) {
            monitor.clearHistory(inc.serviceName); // before RECOVERED so stale failures cannot reopen an incident
            incidents.transition(inc.id, IncidentState.REMEDIATING, IncidentState.RECOVERED,
                    "Verified healthy after " + a.actionType);
        } else {
            incidents.transition(inc.id, IncidentState.REMEDIATING, IncidentState.ANALYZING,
                    a.actionType + " did not restore health; re-analyzing");
        }
    }

    private long count(Incident inc, ActionType t) {
        return actions.countByIncidentIdAndEpochAndActionTypeAndStatusIn(inc.id, inc.epoch, t, EXECUTED);
    }
}
