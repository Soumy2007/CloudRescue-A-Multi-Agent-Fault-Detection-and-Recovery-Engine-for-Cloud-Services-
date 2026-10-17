package com.cloudrescue.incident;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Incident lifecycle. Allowed transitions are explicit so invalid ones fail loudly.
 * ANALYZING -> FAILED is allowed so a policy can give up before any action was scheduled.
 */
public enum IncidentState {
    DETECTED, ANALYZING, REMEDIATING, RECOVERED, FAILED, ESCALATED;

    private static final Map<IncidentState, Set<IncidentState>> ALLOWED = Map.of(
            DETECTED, EnumSet.of(ANALYZING),
            ANALYZING, EnumSet.of(REMEDIATING, ESCALATED, FAILED),
            REMEDIATING, EnumSet.of(RECOVERED, ANALYZING, FAILED),
            FAILED, EnumSet.of(ANALYZING),        // explicit re-open only
            ESCALATED, EnumSet.of(ANALYZING),     // explicit re-open only
            RECOVERED, EnumSet.noneOf(IncidentState.class));

    public boolean canTransitionTo(IncidentState next) {
        return ALLOWED.get(this).contains(next);
    }

    /** States the automatic recovery worker drives. */
    public boolean isActive() {
        return this == DETECTED || this == ANALYZING || this == REMEDIATING;
    }
}
