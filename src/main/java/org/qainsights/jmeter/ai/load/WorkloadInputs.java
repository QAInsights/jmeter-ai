package org.qainsights.jmeter.ai.load;

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
        String planFileName) {

    public enum TargetType {
        REQUEST_RATE,
        ITERATION_RATE,
        USERS
    }
}
