package com.cloudrescue.simulator;

public record HealthResult(boolean ok, int latencyMs, int status) {}
