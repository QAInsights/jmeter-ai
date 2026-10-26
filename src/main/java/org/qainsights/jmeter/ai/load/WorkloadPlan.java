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
        long heapPerGeneratorMb,
        String threadCapacityBasis,
        Double cpuSamplesPerSecond,
        Integer generatorVcpus,
        Double generatorRamGb,
        boolean measuredCapacitySet,
        String cmdCommand,
        String powershellCommand,
        WorkloadShapeConfig shapeConfig,
        List<WorkloadStage> stages,
        String openModelSchedule) {

    public WorkloadPlan {
        assumptions = List.copyOf(assumptions);
        warnings = List.copyOf(warnings);
        samplerMix = samplerMix == null ? SamplerMix.classify(List.of()) : samplerMix;
        notes = List.copyOf(notes);
        generatorHosts = List.copyOf(generatorHosts);
        threadCapacityBasis = threadCapacityBasis == null ? "default" : threadCapacityBasis;
        cmdCommand = cmdCommand == null ? cliCommand : cmdCommand;
        powershellCommand = powershellCommand == null ? cliCommand : powershellCommand;
        shapeConfig = shapeConfig == null ? WorkloadShapeConfig.load() : shapeConfig;
        stages = stages == null ? List.of() : List.copyOf(stages);
        openModelSchedule = openModelSchedule == null ? "" : openModelSchedule;
    }

    public WorkloadPlan(WorkloadInputs.TargetType targetType, double targetValue, int threads,
                        int samplersPerIteration, Double targetRequestsPerSecond,
                        Double expectedRequestsPerSecond, Double rpm, long rampSeconds,
                        long holdSeconds, long durationSeconds, int agents, Integer perAgentThreads,
                        Double perAgentRpm, long responseMillis, long thinkMillis, String propertyPrefix,
                        List<String> assumptions, List<String> warnings, String cliCommand,
                        SamplerMix samplerMix, List<String> notes, List<String> generatorHosts,
                        int maxThreadsPerGenerator, int generatorsNeeded, long heapPerGeneratorMb,
                        String threadCapacityBasis, Double cpuSamplesPerSecond, Integer generatorVcpus,
                        Double generatorRamGb, boolean measuredCapacitySet, String cmdCommand,
                        String powershellCommand) {
        this(targetType, targetValue, threads, samplersPerIteration, targetRequestsPerSecond,
                expectedRequestsPerSecond, rpm, rampSeconds, holdSeconds, durationSeconds, agents,
                perAgentThreads, perAgentRpm, responseMillis, thinkMillis, propertyPrefix,
                assumptions, warnings, cliCommand, samplerMix, notes, generatorHosts,
                maxThreadsPerGenerator, generatorsNeeded, heapPerGeneratorMb, threadCapacityBasis,
                cpuSamplesPerSecond, generatorVcpus, generatorRamGb, measuredCapacitySet,
                cmdCommand, powershellCommand, WorkloadShapeConfig.load(), List.of(), "");
    }

    public WorkloadPlan(WorkloadInputs.TargetType targetType, double targetValue, int threads,
                        int samplersPerIteration, Double targetRequestsPerSecond,
                        Double expectedRequestsPerSecond, Double rpm, long rampSeconds,
                        long holdSeconds, long durationSeconds, int agents, Integer perAgentThreads,
                        Double perAgentRpm, long responseMillis, long thinkMillis, String propertyPrefix,
                        List<String> assumptions, List<String> warnings, String cliCommand,
                        SamplerMix samplerMix, List<String> notes, List<String> generatorHosts,
                        int maxThreadsPerGenerator, int generatorsNeeded, long heapPerGeneratorMb,
                        String threadCapacityBasis, Double cpuSamplesPerSecond, Integer generatorVcpus,
                        Double generatorRamGb, boolean measuredCapacitySet) {
        this(targetType, targetValue, threads, samplersPerIteration, targetRequestsPerSecond,
                expectedRequestsPerSecond, rpm, rampSeconds, holdSeconds, durationSeconds, agents,
                perAgentThreads, perAgentRpm, responseMillis, thinkMillis, propertyPrefix,
                assumptions, warnings, cliCommand, samplerMix, notes, generatorHosts,
                maxThreadsPerGenerator, generatorsNeeded, heapPerGeneratorMb, threadCapacityBasis,
                cpuSamplesPerSecond, generatorVcpus, generatorRamGb, measuredCapacitySet,
                cliCommand, cliCommand);
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
                warnings, cliCommand, SamplerMix.classify(List.of()), List.of(), List.of(), 1000, 1, 1024,
                "default", null, null, null, false);
    }

    public String command() {
        return cliCommand;
    }

    public String command(CommandShell shell) {
        return switch (shell) {
            case BASH -> cliCommand;
            case CMD -> cmdCommand;
            case POWERSHELL -> powershellCommand;
        };
    }

    public String capacityPerGenerator() {
        String threadCapacity = maxThreadsPerGenerator + " threads (" + threadCapacityBasis + ")";
        return cpuSamplesPerSecond == null ? threadCapacity : threadCapacity + ", "
                + WorkloadCalculator.format(cpuSamplesPerSecond) + " samples/s (CPU estimate)";
    }
}
