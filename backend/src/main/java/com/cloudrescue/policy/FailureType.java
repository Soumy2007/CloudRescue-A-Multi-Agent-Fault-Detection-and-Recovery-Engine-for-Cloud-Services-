package com.cloudrescue.policy;

/** Failure classes the monitor can infer from probe results (it never sees the injected fault directly). */
public enum FailureType {
    UNAVAILABLE, HIGH_LATENCY, HIGH_ERROR_RATE, REGION_SUSPECTED
}
