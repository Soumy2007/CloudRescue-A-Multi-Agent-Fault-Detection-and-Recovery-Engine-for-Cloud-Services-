package com.cloudrescue.incident;

import com.cloudrescue.common.Errors.InvalidTransitionException;
import com.cloudrescue.common.Errors.NotFoundException;
import com.cloudrescue.policy.ActionType;
import com.cloudrescue.policy.FailureType;
import com.cloudrescue.simulator.ServiceNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Owns incident persistence and the state machine. Every transition and its audit row are written in one
 * transaction; the @Version column makes concurrent conflicting transitions fail instead of both applying.
 */
@Service
public class IncidentService {
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
