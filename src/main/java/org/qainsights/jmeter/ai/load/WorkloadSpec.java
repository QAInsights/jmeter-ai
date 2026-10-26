package org.qainsights.jmeter.ai.load;

import java.util.List;

/** Parsed values from a deterministic {@code @load} request. */
public record WorkloadSpec(
        Double requestsPerSecond,
        Double messagesPerSecond,
        Double iterationsPerSecond,
        Integer users,
        Long rampSeconds,
        Long holdSeconds,
        Long thinkMillis,
        Long responseMillis,
        Integer agents,
        Integer generatorVcpus,
        Double generatorRamGb,
        List<String> unsupported,
        List<String> unrecognized,
        List<String> duplicates,
        WorkloadShapeConfig shapeConfig) {

    public WorkloadSpec {
        unsupported = List.copyOf(unsupported);
        unrecognized = List.copyOf(unrecognized);
        duplicates = List.copyOf(duplicates);
        shapeConfig = shapeConfig == null ? WorkloadShapeConfig.load() : shapeConfig;
    }

    public WorkloadSpec(Double requestsPerSecond, Double messagesPerSecond, Double iterationsPerSecond,
                        Integer users, Long rampSeconds, Long holdSeconds, Long thinkMillis,
                        Long responseMillis, Integer agents, Integer generatorVcpus, Double generatorRamGb,
                        List<String> unsupported, List<String> unrecognized, List<String> duplicates) {
        this(requestsPerSecond, messagesPerSecond, iterationsPerSecond, users, rampSeconds, holdSeconds,
                thinkMillis, responseMillis, agents, generatorVcpus, generatorRamGb,
                unsupported, unrecognized, duplicates, WorkloadShapeConfig.load());
    }

    public WorkloadSpec(Double requestsPerSecond, Double iterationsPerSecond, Integer users,
                        Long rampSeconds, Long holdSeconds, Long thinkMillis, Long responseMillis,
                        Integer agents, List<String> unsupported, List<String> unrecognized,
                        List<String> duplicates) {
        this(requestsPerSecond, null, iterationsPerSecond, users, rampSeconds, holdSeconds,
                thinkMillis, responseMillis, agents, null, null, unsupported, unrecognized, duplicates);
    }

    public WorkloadSpec(Double requestsPerSecond, Double messagesPerSecond, Double iterationsPerSecond,
                        Integer users, Long rampSeconds, Long holdSeconds, Long thinkMillis,
                        Long responseMillis, Integer agents, List<String> unsupported,
                        List<String> unrecognized, List<String> duplicates) {
        this(requestsPerSecond, messagesPerSecond, iterationsPerSecond, users, rampSeconds, holdSeconds,
                thinkMillis, responseMillis, agents, null, null, unsupported, unrecognized, duplicates);
    }

    public boolean isEmpty() {
        return requestsPerSecond == null && messagesPerSecond == null && iterationsPerSecond == null && users == null
                && rampSeconds == null && holdSeconds == null && thinkMillis == null
                && responseMillis == null && agents == null && generatorVcpus == null && generatorRamGb == null
                && shapeConfig.shape() == WorkloadShape.LOAD;
    }
}
