package com.cloudrescue.incident;

import com.cloudrescue.policy.FailureType;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "incidents")
public class Incident {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String serviceName;
    public Long nodeId;
    @Enumerated(EnumType.STRING)
    public FailureType failureType;
    @Enumerated(EnumType.STRING)
    public IncidentState state;
    public String policyName;
    public Long faultInjectionId;
    public Instant detectedAt;
    public Instant recoveredAt;
    public Instant closedAt;
    /** Incremented on every re-open; policy counters only consider actions of the current epoch. */
    public int epoch;
    public String forcedAction;
    public String summary;
    /** Optimistic lock: two workers cannot both apply a transition from the same version. */
    @Version
    public long version;
}
