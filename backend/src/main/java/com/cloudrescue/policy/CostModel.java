package com.cloudrescue.policy;

/** Simulated cost assumptions (abstract units). These are assumptions, NOT measurements. */
public final class CostModel {
    public static final double RETRY = 0.1;
    public static final double RESTART = 1.0;
    public static final double FAILOVER = 5.0;

    private CostModel() {}

    public static double of(ActionType t) {
        return switch (t) {
            case RETRY -> RETRY;
            case RESTART -> RESTART;
            case FAILOVER -> FAILOVER;
            default -> 0;
        };
    }
}
