package com.cloudrescue.policy;

import jakarta.persistence.*;

import java.time.Instant;

/** Audit of one policy decision: what was chosen, why, and which alternatives were rejected. */
@Entity
@Table(name = "policy_runs")
public class PolicyRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long incidentId;
    public String policyName;
    @Enumerated(EnumType.STRING)
    public FailureType failureType;
    @Enumerated(EnumType.STRING)
    public ActionType decisionAction;
    public long delayMs;
    public String reason;
    public String rejectedAlternatives;
    public Instant decidedAt;
}
