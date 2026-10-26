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
        List<JdbcPool> jdbcPools,
        Integer generatorVcpus,
        Double generatorRamGb,
        Double samplesPerVcpu,
        boolean scriptHeavy,
        WorkloadShapeConfig shapeConfig) {

    public WorkloadInputs {
        generatorHosts = generatorHosts == null ? List.of() : List.copyOf(generatorHosts);
        samplerClasses = samplerClasses == null ? List.of() : List.copyOf(samplerClasses);
        jdbcPools = jdbcPools == null ? List.of() : List.copyOf(jdbcPools);
        shapeConfig = shapeConfig == null ? WorkloadShapeConfig.load() : shapeConfig;
    }

    public WorkloadInputs(TargetType targetType, double targetValue, Double rateCapRps, Long responseMillis,
                          Long thinkMillis, Long rampSeconds, Long holdSeconds, Integer agents,
                          int samplersPerIteration, String propertyPrefix, String planFileName,
                          Double messagesPerSample, Integer maxThreadsPerGenerator,
                          List<String> generatorHosts, List<Class<?>> samplerClasses,
                          List<JdbcPool> jdbcPools, Integer generatorVcpus, Double generatorRamGb,
                          Double samplesPerVcpu, boolean scriptHeavy) {
        this(targetType, targetValue, rateCapRps, responseMillis, thinkMillis, rampSeconds, holdSeconds,
                agents, samplersPerIteration, propertyPrefix, planFileName, messagesPerSample,
                maxThreadsPerGenerator, generatorHosts, samplerClasses, jdbcPools, generatorVcpus,
                generatorRamGb, samplesPerVcpu, scriptHeavy, WorkloadShapeConfig.load());
    }

    public WorkloadInputs(TargetType targetType, double targetValue, Double rateCapRps, Long responseMillis,
                          Long thinkMillis, Long rampSeconds, Long holdSeconds, Integer agents,
                          int samplersPerIteration, String propertyPrefix, String planFileName) {
        this(targetType, targetValue, rateCapRps, responseMillis, thinkMillis, rampSeconds, holdSeconds,
                agents, samplersPerIteration, propertyPrefix, planFileName, 1.0, null,
                List.of(), List.of(), List.of(), null, null, null, false, WorkloadShapeConfig.load());
    }

    public WorkloadInputs(TargetType targetType, double targetValue, Double rateCapRps, Long responseMillis,
                          Long thinkMillis, Long rampSeconds, Long holdSeconds, Integer agents,
                          int samplersPerIteration, String propertyPrefix, String planFileName,
                          Double messagesPerSample, Integer maxThreadsPerGenerator,
                          List<String> generatorHosts, List<Class<?>> samplerClasses,
                          List<JdbcPool> jdbcPools) {
        this(targetType, targetValue, rateCapRps, responseMillis, thinkMillis, rampSeconds, holdSeconds,
                agents, samplersPerIteration, propertyPrefix, planFileName, messagesPerSample,
                maxThreadsPerGenerator, generatorHosts, samplerClasses, jdbcPools,
                null, null, null, false, WorkloadShapeConfig.load());
    }

    public enum TargetType {
        REQUEST_RATE,
        ITERATION_RATE,
        MESSAGE_RATE,
        USERS
    }
}
