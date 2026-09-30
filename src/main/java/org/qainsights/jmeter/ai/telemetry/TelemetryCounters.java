package org.qainsights.jmeter.ai.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-feature usage counts. {@link #subtract} removes exactly the
 * counts captured by a {@link #snapshot}, so increments that happen while a
 * send is in flight survive.
 */
public class TelemetryCounters {
    private final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();

    public void increment(TelemetryFeature feature) {
        counts.merge(feature.wireName(), 1, Integer::sum);
    }

    /** Restores persisted counts saved from an earlier run. */
    public void loadPending(Map<String, Integer> pending) {
        if (pending == null) {
            return;
        }
        pending.forEach((k, v) -> {
            if (v != null && v > 0) {
                counts.merge(k, v, Integer::sum);
            }
        });
    }

    /** Returns a stable copy of wire name to count, zero counts omitted. */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> copy = new LinkedHashMap<>();
        counts.forEach((k, v) -> {
            if (v > 0) {
                copy.put(k, v);
            }
        });
        return copy;
    }

    public void subtract(Map<String, Integer> sent) {
        sent.forEach((k, v) ->
                counts.merge(k, -v, (cur, delta) -> {
                    int next = cur + delta;
                    return next > 0 ? next : null;
                }));
    }
}
