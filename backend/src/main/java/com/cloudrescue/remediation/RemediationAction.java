package com.cloudrescue.remediation;

import com.cloudrescue.policy.ActionType;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "remediation_actions")
public class RemediationAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long incidentId;
    public int seqNo;
    public int epoch;
    @Enumerated(EnumType.STRING)
    public ActionType actionType;
    @Enumerated(EnumType.STRING)
    public ActionStatus status;
    public String idempotencyKey;
    public Instant scheduledAt;
    public Instant startedAt;
    public Instant finishedAt;
    public Long durationMs;
    public int attempts;
    public String resultMessage;
    public Boolean verifiedHealthy;
}
