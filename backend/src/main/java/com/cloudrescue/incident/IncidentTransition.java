package com.cloudrescue.incident;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "incident_state_transitions")
public class IncidentTransition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long incidentId;
    @Enumerated(EnumType.STRING)
    public IncidentState fromState;
    @Enumerated(EnumType.STRING)
    public IncidentState toState;
    public String reason;
    public Instant occurredAt;

    public IncidentTransition() {}

    public IncidentTransition(Long incidentId, IncidentState from, IncidentState to, String reason, Instant at) {
        this.incidentId = incidentId;
        this.fromState = from;
        this.toState = to;
        this.reason = reason;
        this.occurredAt = at;
    }
}
