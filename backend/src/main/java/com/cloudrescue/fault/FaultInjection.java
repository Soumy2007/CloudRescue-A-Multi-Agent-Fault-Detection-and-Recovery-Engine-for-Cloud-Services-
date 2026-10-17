package com.cloudrescue.fault;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "fault_injections")
public class FaultInjection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Enumerated(EnumType.STRING)
    public FaultType faultType;
    public Long targetNodeId;
    public String targetRegion;
    public double severity;
    public Instant startedAt;
    public Instant scheduledEndAt;
    public Instant endedAt;
    public String endReason;
}
