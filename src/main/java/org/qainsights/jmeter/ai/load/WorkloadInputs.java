package org.qainsights.jmeter.ai.load;

import java.util.List;

/** User-editable inputs to the workload calculation. Null values select documented defaults. */
public record WorkloadInputs(
        TargetType targetType,
        double targetValue,
        Double rateCapRps,
        Long responseMillis,
        Long thinkMillis,
        Long rampSeconds,
        Long holdSeconds,
        Integer agents,
        int samplersPerIteration,
        String propertyPrefix,
        String planFileName,
        Double messagesPerSample,
        Integer maxThreadsPerGenerator,
        List<String> generatorHosts,
        List<Class<?>> samplerClasses,
        List<JdbcPool> jdbcPools) {

    public WorkloadInputs {
        generatorHosts = generatorHosts == null ? List.of() : List.copyOf(generatorHosts);
        samplerClasses = samplerClasses == null ? List.of() : List.copyOf(samplerClasses);
        jdbcPools = jdbcPools == null ? List.of() : List.copyOf(jdbcPools);
    }

    public WorkloadInputs(TargetType targetType, double targetValue, Double rateCapRps, Long responseMillis,
                          Long thinkMillis, Long rampSeconds, Long holdSeconds, Integer agents,
                          int samplersPerIteration, String propertyPrefix, String planFileName) {
        this(targetType, targetValue, rateCapRps, responseMillis, thinkMillis, rampSeconds, holdSeconds,
                agents, samplersPerIteration, propertyPrefix, planFileName, 1.0, 1000,
                List.of(), List.of(), List.of());
    }

    public enum TargetType {
        REQUEST_RATE,
        ITERATION_RATE,
        MESSAGE_RATE,
        USERS
    }
}
