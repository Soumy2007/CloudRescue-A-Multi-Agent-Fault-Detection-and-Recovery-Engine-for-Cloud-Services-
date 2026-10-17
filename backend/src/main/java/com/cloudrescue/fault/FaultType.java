package com.cloudrescue.fault;

public enum FaultType {
    CRASH,            // service unavailable until restarted
    LATENCY,          // severity = extra latency in ms
    ERROR_RATE,       // severity = probability of HTTP 500
    REGION_OUTAGE,    // whole logical region unavailable
    RESTART_FAILURE,  // recovery-action failure: severity = probability a restart fails
    ACK_LOSS          // recovery-action failure: severity = probability an acknowledgement is lost
}
