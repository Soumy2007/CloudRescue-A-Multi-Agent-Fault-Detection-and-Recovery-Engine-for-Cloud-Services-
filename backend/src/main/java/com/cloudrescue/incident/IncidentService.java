package com.cloudrescue.incident;

import com.cloudrescue.common.Errors.InvalidTransitionException;
import com.cloudrescue.common.Errors.NotFoundException;
import com.cloudrescue.observability.AfterCommit;
import com.cloudrescue.observability.EventLog;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.simulator.ServiceNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owns incident persistence and the state machine. Every transition and its audit row are written in one
 * transaction; the @Version column makes concurrent conflicting transitions fail instead of both applying.
 */
@Service
public class IncidentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidents;
    private final IncidentTransitionRepository transitions;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, IncidentTransitionRepository transitions, Clock clock) {
        this.incidents = incidents;
        this.transitions = transitions;
        this.clock = clock;
    }

    /** The partial unique index uq_one_unresolved_incident_per_service rejects a second open incident. */
    @Transactional
    public Incident open(ServiceNode node, FailureType type, String policyName, Long faultId, String summary) {
        Incident i = new Incident();
        i.serviceName = node.serviceName;
        i.nodeId = node.id;
        i.failureType = type;
        i.state = IncidentState.DETECTED;
        i.policyName = policyName;
        i.faultInjectionId = faultId;
        i.detectedAt = clock.instant();
        i.summary = summary;
        incidents.saveAndFlush(i);
        transitions.save(new IncidentTransition(i.id, null, IncidentState.DETECTED, summary, i.detectedAt));
        return i;
    }

    @Transactional
    public Incident transition(long id, IncidentState expectedFrom, IncidentState to, String reason) {
        Incident i = get(id);
        if (i.state != expectedFrom) {
            throw new InvalidTransitionException("Incident " + id + " is " + i.state + ", expected " + expectedFrom);
        }
        apply(i, to, reason);
        return i;
    }

    /** Explicit operator action: FAILED/ESCALATED -> ANALYZING, optionally forcing the next action. */
    @Transactional
    public Incident reopen(long id, ActionType forced) {
        Incident i = get(id);
        if (i.state != IncidentState.FAILED && i.state != IncidentState.ESCALATED) {
            throw new InvalidTransitionException("Only FAILED or ESCALATED incidents can be re-opened (is " + i.state + ")");
        }
        if (forced != null && !forced.isExecutable()) {
            throw new InvalidTransitionException("Forced action must be RETRY, RESTART or FAILOVER");
        }
        i.epoch++;
        i.forcedAction = forced == null ? null : forced.name();
        apply(i, IncidentState.ANALYZING, "Re-opened by operator" + (forced == null ? "" : " with forced action " + forced));
        return i;
    }

    /**
     * Re-opens a FAILED or ESCALATED incident because the service is failing again. Without this, the one-unresolved-
     * incident-per-service rule would let an old failed incident block recovery of every later failure.
     *
     * Bounded on purpose: only after the incident has sat in its final state for {@code minAge}, and only while
     * {@code epoch < maxEpoch} (operator re-opens count too), so a service that can never be repaired is not retried
     * forever. The incident adopts the CURRENT policy and the freshly observed failure type, and it still has to pass
     * the normal verified-recovery rules to reach RECOVERED.
     */
      // Edit in this method to add a new parameter for the new fault ID 3/11/2026
    @Transactional
public Optional<Incident> reopenIfStale(
        long id,
        String policyName,
        FailureType failureType,
        Long newFaultId,
        Duration minAge,
        int maxEpoch) {

    Incident i = get(id);

    if (i.state != IncidentState.FAILED &&
            i.state != IncidentState.ESCALATED) {
        return Optional.empty();
    }

    // Require a genuinely new active fault.
    if (newFaultId == null || newFaultId.equals(i.faultInjectionId)) {
        return Optional.empty();
    }

    if (i.epoch >= maxEpoch) {
        return Optional.empty();
    }

    List<IncidentTransition> history =
            transitions.findByIncidentIdOrderByIdAsc(id);

    Instant since = history.isEmpty()
            ? i.detectedAt
            : history.get(history.size() - 1).occurredAt;

    if (Duration.between(since, clock.instant()).compareTo(minAge) < 0) {
        return Optional.empty();
    }

    // Store the new fault ID.
    i.faultInjectionId = newFaultId;
    i.epoch++;
    i.forcedAction = null;
    i.policyName = policyName;
    i.failureType = failureType;

    apply(i, IncidentState.ANALYZING,
            "Auto re-opened for new fault #" + newFaultId
                    + " (policy " + policyName
                    + ", failure " + failureType
                    + ", epoch " + i.epoch + ")");

    return Optional.of(i);
}
// edit end point 3/11/2026
    @Transactional
    public void consumeForcedAction(long id) {
        Incident i = get(id);
        i.forcedAction = null;
        incidents.saveAndFlush(i);
    }

    private void apply(Incident i, IncidentState to, String reason) {
        IncidentState from = i.state;
        if (!from.canTransitionTo(to)) {
            throw new InvalidTransitionException("Transition " + from + " -> " + to + " is not allowed");
        }
        Instant now = clock.instant();
        i.state = to;
        if (to == IncidentState.RECOVERED) {
            i.recoveredAt = now;
            i.closedAt = now;
        }
        incidents.saveAndFlush(i);   // flush so an optimistic-lock conflict surfaces here
        transitions.save(new IncidentTransition(i.id, from, to, reason, now));

        // Logged after commit so a rolled-back and re-run step does not produce a misleading duplicate line.
        final Double recoverySec = to == IncidentState.RECOVERED
                ? Duration.between(i.detectedAt, now).toMillis() / 1000.0 : null;
        AfterCommit.run(() -> EventLog.info(log, "incident.transition",
                "incident_id", i.id, "service", i.serviceName, "from", from, "to", to,
                "epoch", i.epoch, "reason", reason, "recovery_duration_s", recoverySec));
    }

    @Transactional(readOnly = true)
    public Incident get(long id) {
        return incidents.findById(id).orElseThrow(() -> new NotFoundException("Incident " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<Incident> page(IncidentState state, Pageable pageable) {
        return state == null ? incidents.findAll(pageable) : incidents.findByState(state, pageable);
    }

    @Transactional(readOnly = true)
    public List<IncidentTransition> history(long id) {
        return transitions.findByIncidentIdOrderByIdAsc(id);
    }
}
