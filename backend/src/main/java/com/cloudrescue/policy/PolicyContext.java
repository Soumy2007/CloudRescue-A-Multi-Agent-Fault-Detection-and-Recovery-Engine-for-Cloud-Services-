package com.cloudrescue.policy;

/** Everything a policy may look at. Counts only include actions of the current incident epoch. */
public record PolicyContext(
        FailureType failureType,
        int retries,
        int restarts,
        int failovers,
        long elapsedSec,
        boolean backupHealthy) {

    public int totalActions() {
        return retries + restarts + failovers;
    }
}
