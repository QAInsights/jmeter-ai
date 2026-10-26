package org.qainsights.jmeter.ai.load;

import java.util.List;

/** Parsed values from a deterministic {@code @load} request. */
public record WorkloadSpec(
        Double requestsPerSecond,
        Double iterationsPerSecond,
        Integer users,
        Long rampSeconds,
        Long holdSeconds,
        Long thinkMillis,
        Long responseMillis,
        Integer agents,
        List<String> unsupported,
        List<String> unrecognized,
        List<String> duplicates) {

    public WorkloadSpec {
        unsupported = List.copyOf(unsupported);
        unrecognized = List.copyOf(unrecognized);
        duplicates = List.copyOf(duplicates);
    }

    public boolean isEmpty() {
        return requestsPerSecond == null && iterationsPerSecond == null && users == null
                && rampSeconds == null && holdSeconds == null && thinkMillis == null
                && responseMillis == null && agents == null;
    }
}
