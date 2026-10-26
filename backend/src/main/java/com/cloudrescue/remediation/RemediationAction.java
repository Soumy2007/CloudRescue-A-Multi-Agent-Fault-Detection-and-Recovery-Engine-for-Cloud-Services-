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
    /** Node this action operates on, fixed before execution so retries and replays stay identical. */
    public Long targetNodeId;
    /** Node that must be serving traffic after the action (restart/retry: the target; failover: the backup). */
    public Long expectedActiveNodeId;
    @Enumerated(EnumType.STRING)
    public VerificationOutcome outcome;
    /** Optimistic lock: two workers cannot both overwrite the same action's state. */
    @Version
    public long version;
}
