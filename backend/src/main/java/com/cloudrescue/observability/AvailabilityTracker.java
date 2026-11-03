package com.cloudrescue.observability;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rolling availability of each logical service, as seen by the monitor in the SIMULATOR.
 *
 * One sample is recorded per monitor poll: did the node currently SERVING TRAFFIC for the service answer its health
 * probe? The ratio is successes / samples over the last {@code window} samples (about window x poll-interval seconds).
 * Unlike the monitor's incident-detection history, these samples are never cleared on recovery, so an outage stays
 * visible in the ratio after the incident is resolved. This is a simulated measurement, not real infrastructure
 * monitoring. Samples live in memory and start empty after an application restart.
 */
public final class AvailabilityTracker {
    private final int window;
    private final Map<String, Deque<Boolean>> samples = new HashMap<>();

    public AvailabilityTracker(int window) {
        if (window < 1) throw new IllegalArgumentException("window must be >= 1");
        this.window = window;
    }

    public int window() {
        return window;
    }

    public synchronized void record(String service, boolean ok) {
        Deque<Boolean> d = samples.computeIfAbsent(service, k -> new ArrayDeque<>());
        d.addLast(ok);
        while (d.size() > window) d.removeFirst();
    }

    public synchronized int sampleCount(String service) {
        Deque<Boolean> d = samples.get(service);
        return d == null ? 0 : d.size();
    }

    /** Fraction of successful samples in [0,1], or NaN when no samples exist yet. */
    public synchronized double ratio(String service) {
        Deque<Boolean> d = samples.get(service);
        if (d == null || d.isEmpty()) return Double.NaN;
        long ok = d.stream().filter(Boolean::booleanValue).count();
        return (double) ok / d.size();
    }

    public synchronized Set<String> services() {
        return new TreeSet<>(samples.keySet());
    }
}
