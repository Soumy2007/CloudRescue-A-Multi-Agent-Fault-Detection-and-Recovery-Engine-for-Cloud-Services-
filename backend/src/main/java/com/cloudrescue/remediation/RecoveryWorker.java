package com.cloudrescue.remediation;

import com.cloudrescue.common.Errors.ConflictException;
import com.cloudrescue.common.Errors.NotFoundException;
import com.cloudrescue.incident.Incident;
import com.cloudrescue.incident.IncidentRepository;
import com.cloudrescue.incident.IncidentService;
import com.cloudrescue.incident.IncidentState;
import com.cloudrescue.monitor.MonitorService;
import com.cloudrescue.observability.AfterCommit;
import com.cloudrescue.observability.EventFormat;
import com.cloudrescue.observability.EventLog;
import com.cloudrescue.observability.RecoveryMetrics;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.PolicyContext;
import com.cloudrescue.policy.PolicyRegistry;
import com.cloudrescue.policy.PolicyRun;
import com.cloudrescue.policy.PolicyRunRepository;
import com.cloudrescue.policy.RecoveryDecision;
import com.cloudrescue.simulator.ServiceNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Drives open incidents through DETECTED -> ANALYZING -> REMEDIATING -> RECOVERED/FAILED/ESCALATED.
 * Decision logic lives in the policies; execution lives in RemediationService; this class only orchestrates.
 * State is durable: after a crash the next tick resumes from what PostgreSQL says.
 *
 * Observability: each step sets incident_id / service in the logging MDC, and records metrics and log lines through
 * AfterCommit, so a step that rolls back and is re-run is observed once, not twice. Observation never changes what
 * the recovery logic does.
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
    private final TransactionTemplate tx;
    private final ServiceNodeRepository nodes;
    private final RecoveryMetrics metrics;

    public RecoveryWorker(IncidentRepository incidentRepo, IncidentService incidents, RemediationActionRepository actions,
                          PolicyRunRepository policyRuns, PolicyRegistry policies, RemediationService remediation,
                          MonitorService monitor, Clock clock, PlatformTransactionManager txManager,
                          ServiceNodeRepository nodes, RecoveryMetrics metrics,
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
        this.tx = new TransactionTemplate(txManager);
        this.nodes = nodes;
        this.metrics = metrics;
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
                log.error(EventFormat.format("recovery.step_failed", "incident_id", i.id, "reason", e.toString()), e);
            }
        }
    }

    void step(long id) {
        // MDC wraps the whole transaction so the after-commit observations still carry incident_id and service.
        try (MDC.MDCCloseable ignored = MDC.putCloseable("incident_id", String.valueOf(id))) {
            tx.executeWithoutResult(status -> {
                Incident inc = incidentRepo.findByIdForUpdate(id)
                        .orElseThrow(() -> new NotFoundException("Incident " + id + " not found"));
                MDC.put("service", inc.serviceName);
                switch (inc.state) {
                    case DETECTED -> incidents.transition(id, IncidentState.DETECTED, IncidentState.ANALYZING, "Analysis started");
                    case ANALYZING -> analyze(inc);
                    case REMEDIATING -> remediate(inc);
                    default -> { }
                }
            });
        } finally {
            MDC.remove("service");
        }
    }

    private void analyze(Incident inc) {
        RecoveryDecision d;
        String policyLabel;
        int priorActions;
        if (inc.forcedAction != null) {
            d = RecoveryDecision.of(ActionType.valueOf(inc.forcedAction), 0, "Operator-requested action");
            policyLabel = "OPERATOR";
            priorActions = 0;
            incidents.consumeForcedAction(inc.id);
        } else {
            int retries = (int) count(inc, ActionType.RETRY);
            int restarts = (int) count(inc, ActionType.RESTART);
            int failovers = (int) count(inc, ActionType.FAILOVER);
           List<com.cloudrescue.incident.IncidentTransition> history =
       incidents.history(inc.id);

Instant recoveryStartedAt = history.stream()
        .filter(t -> t.toState == IncidentState.ANALYZING)
        .map(t -> t.occurredAt)
        .reduce((first, second) -> second)
        .orElse(inc.detectedAt);

d = policies.get(inc.policyName).decide(new PolicyContext(
        inc.failureType, retries, restarts, failovers,
        Duration.between(recoveryStartedAt, clock.instant()).toSeconds(),
        monitor.isBackupHealthy(inc.serviceName)));

            policyLabel = inc.policyName;
            priorActions = retries + restarts + failovers;
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

        final RecoveryDecision decision = d;
        final boolean afterEarlierAction = priorActions > 0 && d.action().isExecutable();
        AfterCommit.run(() -> {
            metrics.policyDecision(policyLabel, decision.action(), afterEarlierAction);
            EventLog.info(log, "policy.decision", "incident_id", inc.id, "service", inc.serviceName,
                    "policy", policyLabel, "failure_type", inc.failureType, "decision", decision.action(),
                    "delay_ms", decision.delayMs(), "after_earlier_action", afterEarlierAction,
                    "reason", decision.reason());
        });

        switch (d.action()) {
            case ESCALATE -> {
                incidents.transition(inc.id, IncidentState.ANALYZING, IncidentState.ESCALATED, d.reason());
                AfterCommit.run(() -> metrics.incidentClosed(IncidentState.ESCALATED));
            }
            case GIVE_UP -> {
                incidents.transition(inc.id, IncidentState.ANALYZING, IncidentState.FAILED, d.reason());
                AfterCommit.run(() -> metrics.incidentClosed(IncidentState.FAILED));
            }
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

        remediation.prepare(inc, a);                 // fix target + expected post-state once, before executing
        a.status = ActionStatus.RUNNING;
        if (a.startedAt == null) a.startedAt = now;
        a = actions.saveAndFlush(a);                 // claim before side effects; optimistic lock rejects stale workers

        RemediationService.ExecutionResult r = remediation.execute(inc, a);
        RemediationService.Verification v = remediation.verify(inc, a, r.actionOk());

        a.status = r.actionOk() ? ActionStatus.SUCCEEDED : ActionStatus.FAILED;
        a.finishedAt = clock.instant();
        a.durationMs = Duration.between(a.startedAt, a.finishedAt).toMillis();
        a.attempts = r.attempts();
        a.resultMessage = r.message().length() > 480 ? r.message().substring(0, 480) : r.message();
        a.outcome = v.outcome();
        a.verifiedHealthy = v.outcome() == VerificationOutcome.VERIFIED;
        a = actions.saveAndFlush(a);

        // Observe the action once, after the step commits (a re-run after rollback is not double counted).
        final RemediationAction done = a;
        final boolean actionOk = r.actionOk();
        final VerificationOutcome outcome = v.outcome();
        final String region = nodes.findById(done.targetNodeId).map(n -> n.region).orElse("unknown");
        AfterCommit.run(() -> {
            metrics.recoveryAttempt(done.actionType, actionOk);
            metrics.verification(outcome);
            EventLog.info(log, "recovery.action.completed", "incident_id", inc.id, "service", inc.serviceName,
                    "region", region, "action_id", done.id, "action", done.actionType,
                    "idempotency_key", done.idempotencyKey, "action_result", actionOk ? "success" : "failure",
                    "outcome", outcome, "attempts", done.attempts, "duration_ms", done.durationMs,
                    "failure_reason", outcome == VerificationOutcome.VERIFIED ? null : done.resultMessage);
        });

        // Recovery needs a successful action AND the expected node serving and healthy. A failed action never
        // recovers an incident, even if a later probe happens to pass.
        switch (v.outcome()) {
            case VERIFIED -> {
                monitor.clearHistory(inc.serviceName); // before RECOVERED so stale failures cannot reopen an incident
                Incident closed = incidents.transition(inc.id, IncidentState.REMEDIATING, IncidentState.RECOVERED,
                        "Verified: " + done.actionType + " succeeded and the expected node is serving and healthy");
                final Duration recovery = Duration.between(closed.detectedAt, closed.recoveredAt);
                final boolean reopened = closed.epoch > 0;
                AfterCommit.run(() -> {
                    metrics.incidentClosed(IncidentState.RECOVERED);
                    metrics.recoveryDuration(recovery, reopened);
                });
            }
            case ACTION_FAILED -> incidents.transition(inc.id, IncidentState.REMEDIATING, IncidentState.ANALYZING,
                    done.actionType + " failed (" + done.resultMessage + "); not recovered, re-analyzing");
            case POSTCONDITION_FAILED -> incidents.transition(inc.id, IncidentState.REMEDIATING, IncidentState.ANALYZING,
                    done.actionType + " reported success but the expected state was not observed; re-analyzing");
        }
    }

    private long count(Incident inc, ActionType t) {
        return actions.countByIncidentIdAndEpochAndActionTypeAndStatusIn(inc.id, inc.epoch, t, EXECUTED);
    }
}
