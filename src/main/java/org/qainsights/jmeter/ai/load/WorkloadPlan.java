package org.qainsights.jmeter.ai.load;

import java.util.List;

/** A calculated, reviewable workload and its JMeter CLI command. */
public record WorkloadPlan(
        WorkloadInputs.TargetType targetType,
        double targetValue,
        int threads,
        int samplersPerIteration,
        Double targetRequestsPerSecond,
        Double expectedRequestsPerSecond,
        Double rpm,
        long rampSeconds,
        long holdSeconds,
        long durationSeconds,
        int agents,
        Integer perAgentThreads,
        Double perAgentRpm,
        long responseMillis,
        long thinkMillis,
        String propertyPrefix,
        List<String> assumptions,
        List<String> warnings,
        String cliCommand) {

    public WorkloadPlan {
        assumptions = List.copyOf(assumptions);
        warnings = List.copyOf(warnings);
    }
}
