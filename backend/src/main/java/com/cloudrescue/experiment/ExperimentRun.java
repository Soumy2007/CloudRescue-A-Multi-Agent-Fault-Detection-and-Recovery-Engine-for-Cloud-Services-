package com.cloudrescue.experiment;

import jakarta.persistence.*;

import java.time.Instant;

/** One trial of one scenario under one policy. */
@Entity
@Table(name = "experiment_runs")
public class ExperimentRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String batchId;
    public String scenario;
    public String policyName;
    public int trialIndex;
    public long seed;
    public boolean idempotencyEnabled;
    public int observationSec;
    public Double detectionDelaySec;
    public Double recoveryTimeSec;
    public Double totalTimeSec;
    public double availability;
    public boolean recovered;
    public boolean escalated;
    public int duplicateOps;
    public double cost;
    public int retries;
    public int restarts;
    public int failovers;
    public Instant createdAt;
}
