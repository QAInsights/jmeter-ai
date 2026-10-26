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
        String cliCommand,
        SamplerMix samplerMix,
        List<String> notes,
        List<String> generatorHosts,
        int maxThreadsPerGenerator,
        int generatorsNeeded,
        long heapPerGeneratorMb) {

    public WorkloadPlan {
        assumptions = List.copyOf(assumptions);
        warnings = List.copyOf(warnings);
        samplerMix = samplerMix == null ? SamplerMix.classify(List.of()) : samplerMix;
        notes = List.copyOf(notes);
        generatorHosts = List.copyOf(generatorHosts);
    }

    public WorkloadPlan(WorkloadInputs.TargetType targetType, double targetValue, int threads,
                        int samplersPerIteration, Double targetRequestsPerSecond,
                        Double expectedRequestsPerSecond, Double rpm, long rampSeconds,
                        long holdSeconds, long durationSeconds, int agents, Integer perAgentThreads,
                        Double perAgentRpm, long responseMillis, long thinkMillis, String propertyPrefix,
                        List<String> assumptions, List<String> warnings, String cliCommand) {
        this(targetType, targetValue, threads, samplersPerIteration, targetRequestsPerSecond,
                expectedRequestsPerSecond, rpm, rampSeconds, holdSeconds, durationSeconds, agents,
                perAgentThreads, perAgentRpm, responseMillis, thinkMillis, propertyPrefix, assumptions,
                warnings, cliCommand, SamplerMix.classify(List.of()), List.of(), List.of(), 1000, 1, 1024);
    }
}
