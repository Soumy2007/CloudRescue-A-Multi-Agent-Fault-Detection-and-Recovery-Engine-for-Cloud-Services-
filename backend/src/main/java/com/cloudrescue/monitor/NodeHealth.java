package com.cloudrescue.monitor;

import java.util.List;

/** Dashboard view of one node. availabilityPct covers the retained probe window only. */
public record NodeHealth(Long id, String serviceName, String region, String role, boolean active,
                         Boolean healthy, Integer latencyMs, Integer status, Double availabilityPct,
                         List<Boolean> recent) {}
