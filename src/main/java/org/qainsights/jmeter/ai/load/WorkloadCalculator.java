package org.qainsights.jmeter.ai.load;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.qainsights.jmeter.ai.load.WorkloadInputs.TargetType.ITERATION_RATE;
import static org.qainsights.jmeter.ai.load.WorkloadInputs.TargetType.MESSAGE_RATE;
import static org.qainsights.jmeter.ai.load.WorkloadInputs.TargetType.REQUEST_RATE;
import static org.qainsights.jmeter.ai.load.WorkloadInputs.TargetType.USERS;

/** Deterministic Little's Law calculator for closed-model JMeter workloads. */
public final class WorkloadCalculator {
    public static final double DEFAULT_SAMPLES_PER_VCPU = 250;
    private static final String RESPONSE_ASSUMPTION =
            "Assumed 500 ms average response time. Measure it with a 1-user run (for example `@fix`) and update it.";
    private static final Pattern SAFE_PLAN_PATH = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final String UNSAFE_CMD_PLAN_CHARACTERS = "\"%^&|<>";
    private static final String ZERO_RAMP_NOTE =
            "Ramps of 0 s are applied as 1 s so the hold before stays flat.";
    private static final Pattern VALID_HOST = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Pattern VALID_PREFIX = Pattern.compile("[A-Za-z0-9._-]+");

    private WorkloadCalculator() {
    }

    public static WorkloadPlan calculate(WorkloadInputs inputs) {
        validate(inputs);
        List<String> assumptions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> stageNotes = new ArrayList<>();
        WorkloadShapeConfig shapeConfig = inputs.shapeConfig();
        WorkloadShape shape = shapeConfig.shape();
        boolean openModel = shape.usesOpenModelThreadGroup();
        List<WorkloadStage> stages = openModel ? buildStages(inputs, stageNotes) : List.of();
        long responseMillis = inputs.responseMillis() == null ? 500 : inputs.responseMillis();
        long thinkMillis = inputs.thinkMillis() == null ? 0 : inputs.thinkMillis();
        long defaultHold = shape == WorkloadShape.ENDURANCE ? 14_400 : 600;
        long holdSeconds = inputs.holdSeconds() == null ? defaultHold : inputs.holdSeconds();
        if (inputs.responseMillis() == null) {
            assumptions.add(RESPONSE_ASSUMPTION);
        }
        if (inputs.holdSeconds() == null && !openModel) {
            assumptions.add(shape == WorkloadShape.ENDURANCE
                    ? "Assumed a 4-hour endurance hold."
                    : "Assumed a 600-second (10-minute) hold.");
        }
        long rampSeconds = inputs.rampSeconds() == null
                ? clamp(Math.round(holdSeconds * 0.1), 60, 900)
                : inputs.rampSeconds();
        if (inputs.rampSeconds() == null && !openModel) {
            assumptions.add("Assumed a " + rampSeconds
                    + "-second ramp-up (10% of the hold, clamped from 60 to 900 seconds).");
        }
        List<String> hosts = inputs.generatorHosts();
        int agents = hosts.isEmpty() ? (inputs.agents() == null ? 1 : inputs.agents()) : hosts.size();
        int maxThreads;
        String capacityBasis;
        if (inputs.maxThreadsPerGenerator() != null) {
            maxThreads = inputs.maxThreadsPerGenerator();
            capacityBasis = "measured";
        } else if (inputs.generatorRamGb() != null) {
            long usableHeapMb = floorTo512(inputs.generatorRamGb() * 1024 * 0.75);
            maxThreads = (int) Math.max(1, Math.min(Integer.MAX_VALUE, (usableHeapMb - 512) / 2));
            capacityBasis = "memory";
        } else {
            maxThreads = 1000;
            capacityBasis = "default";
        }
        double samplesPerVcpu = inputs.samplesPerVcpu() == null
                ? inputs.scriptHeavy() ? DEFAULT_SAMPLES_PER_VCPU / 2 : DEFAULT_SAMPLES_PER_VCPU
                : inputs.samplesPerVcpu();
        Double cpuSamplesPerSecond = inputs.generatorVcpus() == null
                || inputs.maxThreadsPerGenerator() != null
                ? null : inputs.generatorVcpus() * samplesPerVcpu;
        String prefix = inputs.propertyPrefix() == null || inputs.propertyPrefix().isBlank()
                ? "load" : inputs.propertyPrefix();
        String planFile = inputs.planFileName() == null || inputs.planFileName().isBlank()
                ? "plan.jmx" : inputs.planFileName();
        double secondsPerRequest = (responseMillis + thinkMillis) / 1000.0;
        double rps = switch (inputs.targetType()) {
            case REQUEST_RATE -> inputs.targetValue();
            case ITERATION_RATE -> inputs.targetValue() * inputs.samplersPerIteration();
            case MESSAGE_RATE -> inputs.targetValue() / inputs.messagesPerSample();
            case USERS -> inputs.targetValue() / secondsPerRequest;
        };
        double peakIterationRate = openModel
                ? stages.stream().mapToDouble(WorkloadStage::toRate).max().orElseThrow() : 0;
        if (openModel) {
            rps = peakIterationRate * inputs.samplersPerIteration();
        }
        Double targetRps = inputs.targetType() == USERS
                ? inputs.rateCapRps() : Double.valueOf(rps);
        Double rpm = null;
        Double expectedRps = null;
        int threads;

        if (openModel) {
            targetRps = rps;
            expectedRps = rps;
            double iterationSeconds = inputs.samplersPerIteration() * secondsPerRequest;
            double sizedThreads = Math.ceil(peakIterationRate * iterationSeconds * 1.5);
            if (!Double.isFinite(sizedThreads) || sizedThreads > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("The calculated thread count is too large.");
            }
            threads = Math.max(1, (int) sizedThreads);
        } else if (inputs.targetType() == USERS) {
            threads = (int) Math.ceil(inputs.targetValue());
            expectedRps = round2(rps);
            if (inputs.rateCapRps() != null) {
                rpm = round2(inputs.rateCapRps() * 60);
                double achievable = threads / secondsPerRequest;
                if (achievable + 1e-9 < inputs.rateCapRps()) {
                    int suggested = (int) Math.ceil(inputs.rateCapRps() * secondsPerRequest * 1.5);
                    warnings.add(threads + " users can reach at most " + format(achievable)
                            + " samples/s at " + responseMillis + " ms per sample (plus think time);"
                            + " you asked for " + format(inputs.rateCapRps())
                            + ". Use at least " + suggested + " users.");
                }
            } else {
                targetRps = null;
            }
        } else {
            double neededThreads = rps * secondsPerRequest;
            double sizedThreads = Math.ceil(neededThreads * 1.5);
            if (sizedThreads > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("The calculated thread count is too large.");
            }
            threads = Math.max(1, (int) sizedThreads);
            rpm = round2(rps * 60);
        }
        if (openModel) {
            rampSeconds = stages.get(0).seconds();
            holdSeconds = 0;
        }
        long durationSeconds = openModel
                ? stages.stream().map(WorkloadStage::seconds).reduce(0L, Math::addExact)
                : Math.addExact(rampSeconds, holdSeconds);
        boolean distributed = agents > 1 || !hosts.isEmpty();
        Integer perAgentThreads = distributed ? (int) Math.ceil(threads / (double) agents) : null;
        Double perAgentRpm = distributed && rpm != null ? round2(rpm / agents) : null;
        int rampThreads = perAgentThreads == null ? threads : perAgentThreads;
        if (!openModel && (rampSeconds == 0 || rampThreads / (double) rampSeconds > 50)) {
            warnings.add("Steep ramp-up: more than 50 threads start per second, which can overload the generator and skew early results.");
        }
        int threadGeneratorsNeeded = ceilCount(threads, maxThreads);
        double capacityTargetRps = openModel || inputs.targetType() == USERS ? expectedRps : rps;
        int cpuGeneratorsNeeded = cpuSamplesPerSecond == null
                ? 0 : ceilCount(capacityTargetRps, cpuSamplesPerSecond);
        int generatorsNeeded = Math.max(1, Math.max(threadGeneratorsNeeded, cpuGeneratorsNeeded));
        if (agents < generatorsNeeded) {
            if (cpuGeneratorsNeeded > threadGeneratorsNeeded) {
                warnings.add(format(capacityTargetRps) + " samples/s needs " + generatorsNeeded
                        + " generators: each " + inputs.generatorVcpus() + " vCPU generator handles about "
                        + format(cpuSamplesPerSecond) + " samples/s (CPU estimate).");
            } else if (threadGeneratorsNeeded > cpuGeneratorsNeeded) {
                warnings.add(threads + " threads need at least " + generatorsNeeded + " generators: each "
                        + "generator supports " + maxThreads + " threads (" + capacityBasis + " capacity).");
            } else {
                warnings.add(format(capacityTargetRps) + " samples/s and " + threads
                        + " threads need at least " + generatorsNeeded + " generators: each "
                        + inputs.generatorVcpus() + " vCPU generator handles about "
                        + format(cpuSamplesPerSecond) + " samples/s (CPU estimate) and supports "
                        + maxThreads + " threads (" + capacityBasis + " capacity).");
            }
        }
        long threadsPerGenerator = perAgentThreads == null ? threads : perAgentThreads;
        long heapPerGeneratorMb = ceilTo512(Math.max(1024, 512 + 2 * threadsPerGenerator));
        if (inputs.generatorRamGb() != null
                && heapPerGeneratorMb > inputs.generatorRamGb() * 1024 * 0.75) {
            long availableHeapMb = floorTo512(inputs.generatorRamGb() * 1024 * 0.75);
            warnings.add("Each generator needs about " + heapSize(heapPerGeneratorMb)
                    + " heap, but 75% of " + format(inputs.generatorRamGb())
                    + " GB RAM is " + heapSize(availableHeapMb) + ". Add generators or RAM.");
        }
        SamplerMix samplerMix = SamplerMix.classify(inputs.samplerClasses());
        warnings.addAll(protocolWarnings(inputs.jdbcPools(), samplerMix, (int) threadsPerGenerator));
        List<String> notes = protocolNotes(samplerMix);
        notes.addAll(stageNotes);
        if (shape == WorkloadShape.ENDURANCE) {
            notes.add("Soak preparation: disable View Results Tree and other heavy listeners, check CSV recycle/EOF behavior, leave heap headroom, and estimate results-file disk space.");
        }
        if (openModel) {
            notes.add("Each OMTG arrival creates a new thread that runs one iteration. Cookies, logins and Once Only Controllers run for every arrival.");
            notes.add(prefix + ".sN.rate values are iterations per second per generator; one arrival runs one iteration ("
                    + inputs.samplersPerIteration() + " samples).");
            notes.add("JMeter 5.5 or newer is required on every load generator.");
            notes.add("Open Model Thread Group does not cap concurrency. A slow server can cause thread growth.");
        }
        if (shape == WorkloadShape.STRESS) {
            notes.add("The stress plan does not stop by itself. Stop when errors or latency break your SLA.");
        }
        if (inputs.targetType() == ITERATION_RATE) {
            notes.add("Samples per iteration counts each enabled sampler once; If, Loop, Throughput and Transaction (parent sample) controllers change the real number.");
        }
        if (inputs.generatorVcpus() != null || inputs.generatorRamGb() != null) {
            notes.add("CPU and memory limits are estimates. Confirm with a calibration run on one generator.");
        }
        notes.add("Rule of thumb (about 2 MB per thread plus 512 MB). Heavy listeners, large responses or scripts need more; confirm with a short run.");
        if (agents > 1) {
            notes.add("Copy every CSV Data Set file to the same path on each generator.");
            notes.add("Disable RMI SSL (server.rmi.ssl.disable=true) or set up the RMI keystore on every machine.");
            notes.add("Use the same JMeter, Java and plugin versions on every generator.");
            notes.add("-G properties go to all generators; values are per generator.");
            if (hosts.isEmpty()) {
                notes.add("Replace agent1..N with your generator hostnames, or set remote_hosts in jmeter.properties and use -r.");
            }
        }
        boolean cmdPlanFallback = containsUnsafeCmdPlanCharacter(planFile);
        if (cmdPlanFallback) {
            notes.add("The Windows cmd command uses plan.jmx because the plan name contains characters that cannot be safely quoted.");
        }
        String schedule = openModel ? openModelSchedule(stages, prefix, shapeConfig.arrivalMode(),
                distributed ? agents : 1) : "";
        String command = openModel
                ? openModelCliCommand(planFile, prefix, stages, shapeConfig.arrivalMode(),
                agents, hosts, heapPerGeneratorMb, CommandShell.BASH)
                : cliCommand(planFile, prefix, threads, rampSeconds, durationSeconds, rpm,
                thinkMillis, agents, perAgentThreads, perAgentRpm, hosts, heapPerGeneratorMb,
                CommandShell.BASH);
        String cmdCommand = openModel
                ? openModelCliCommand(cmdPlanFallback ? "plan.jmx" : planFile, prefix, stages,
                shapeConfig.arrivalMode(), agents, hosts, heapPerGeneratorMb, CommandShell.CMD)
                : cliCommand(cmdPlanFallback ? "plan.jmx" : planFile, prefix, threads,
                rampSeconds, durationSeconds, rpm, thinkMillis, agents, perAgentThreads,
                perAgentRpm, hosts, heapPerGeneratorMb, CommandShell.CMD);
        String powershellCommand = openModel
                ? openModelCliCommand(planFile, prefix, stages, shapeConfig.arrivalMode(),
                agents, hosts, heapPerGeneratorMb, CommandShell.POWERSHELL)
                : cliCommand(planFile, prefix, threads, rampSeconds,
                durationSeconds, rpm, thinkMillis, agents, perAgentThreads, perAgentRpm,
                hosts, heapPerGeneratorMb, CommandShell.POWERSHELL);
        return new WorkloadPlan(inputs.targetType(), inputs.targetValue(), threads,
                inputs.samplersPerIteration(), targetRps, expectedRps, rpm, rampSeconds, holdSeconds,
                durationSeconds, agents, perAgentThreads, perAgentRpm, responseMillis, thinkMillis,
                prefix, assumptions, warnings, command, samplerMix, notes, hosts, maxThreads,
                generatorsNeeded, heapPerGeneratorMb, capacityBasis, cpuSamplesPerSecond,
                inputs.generatorVcpus(), inputs.generatorRamGb(), inputs.maxThreadsPerGenerator() != null,
                cmdCommand, powershellCommand, shapeConfig, stages, schedule);
    }

    private static void validate(WorkloadInputs inputs) {
        boolean openModel = inputs != null && inputs.shapeConfig().shape().usesOpenModelThreadGroup();
        if (inputs == null || inputs.targetType() == null
                || !openModel && (!Double.isFinite(inputs.targetValue()) || inputs.targetValue() <= 0)) {
            throw new IllegalArgumentException("Enter a positive target value.");
        }
        if (inputs.targetType() != USERS && inputs.rateCapRps() != null) {
            throw new IllegalArgumentException("A rate cap is only available for a concurrent-user target.");
        }
        if (inputs.rateCapRps() != null
                && (!Double.isFinite(inputs.rateCapRps()) || inputs.rateCapRps() <= 0)) {
            throw new IllegalArgumentException("Enter a positive samples-per-second cap.");
        }
        if (inputs.responseMillis() != null && inputs.responseMillis() <= 0) {
            throw new IllegalArgumentException("Average response time must be greater than zero.");
        }
        if (inputs.targetType() == USERS
                && (inputs.targetValue() > Integer.MAX_VALUE
                || inputs.targetValue() != Math.floor(inputs.targetValue()))) {
            throw new IllegalArgumentException("Concurrent users must be a whole number.");
        }
        if (inputs.thinkMillis() != null && inputs.thinkMillis() < 0) {
            throw new IllegalArgumentException("Think time cannot be negative.");
        }
        if (inputs.rampSeconds() != null && inputs.rampSeconds() < 0) {
            throw new IllegalArgumentException("Ramp-up cannot be negative.");
        }
        if (inputs.holdSeconds() != null && inputs.holdSeconds() <= 0) {
            throw new IllegalArgumentException("Hold duration must be greater than zero.");
        }
        if (inputs.agents() != null && inputs.agents() < 1) {
            throw new IllegalArgumentException("Load generators must be at least 1.");
        }
        if (inputs.samplersPerIteration() < 1) {
            throw new IllegalArgumentException("Add at least one sampler to the Thread Group.");
        }
        if (inputs.targetType() == MESSAGE_RATE
                && (inputs.messagesPerSample() == null || !Double.isFinite(inputs.messagesPerSample())
                || inputs.messagesPerSample() <= 0)) {
            throw new IllegalArgumentException("Messages per sample must be greater than zero.");
        }
        if (inputs.maxThreadsPerGenerator() != null && inputs.maxThreadsPerGenerator() < 1) {
            throw new IllegalArgumentException("Max threads per generator must be at least 1.");
        }
        if (inputs.generatorVcpus() != null && inputs.generatorVcpus() < 1) {
            throw new IllegalArgumentException("Generator vCPUs must be at least 1.");
        }
        if (inputs.generatorRamGb() != null && (!Double.isFinite(inputs.generatorRamGb())
                || inputs.generatorRamGb() <= 0
                || inputs.generatorRamGb() * 1024 * 0.75 > Long.MAX_VALUE - 512)) {
            throw new IllegalArgumentException("Generator RAM must be a positive, representable number of GB.");
        }
        if (inputs.samplesPerVcpu() != null && (!Double.isFinite(inputs.samplesPerVcpu())
                || inputs.samplesPerVcpu() <= 0)) {
            throw new IllegalArgumentException("Samples per vCPU must be greater than zero.");
        }
        for (String host : inputs.generatorHosts()) {
            if (!validGeneratorHost(host)) {
                throw new IllegalArgumentException("Enter generator hosts using letters, numbers, dots, underscores, colons, or hyphens.");
            }
        }
        if (inputs.propertyPrefix() != null && !inputs.propertyPrefix().isBlank()
                && !VALID_PREFIX.matcher(inputs.propertyPrefix()).matches()) {
            throw new IllegalArgumentException("Property prefix can use letters, numbers, dots, underscores, and hyphens only.");
        }
    }

    static List<WorkloadStage> buildStages(WorkloadInputs inputs) {
        return buildStages(inputs, new ArrayList<>());
    }

    private static List<WorkloadStage> buildStages(WorkloadInputs inputs, List<String> notes) {
        WorkloadShapeConfig config = inputs.shapeConfig();
        WorkloadShape shape = config.shape();
        long responseMillis = inputs.responseMillis() == null ? 500 : inputs.responseMillis();
        long thinkMillis = inputs.thinkMillis() == null ? 0 : inputs.thinkMillis();
        List<WorkloadStage> stages = new ArrayList<>();
        switch (shape) {
            case STEP -> {
                double start = targetToIterations(config.startTarget(), inputs);
                double end = targetToIterations(config.endTarget(), inputs);
                double increment = targetToIterations(config.stepTarget(), inputs);
                long hold = positiveDuration(config.stepDurationSeconds(), 300, "Step duration");
                long ramp = nonNegativeDuration(config.rampSeconds(),
                        Math.min(10, hold / 5), "Step ramp");
                if (end < start || increment <= 0) {
                    throw new IllegalArgumentException("Step end must be at or above its start, with a positive step size.");
                }
                addRampStage(stages, 0, start, ramp, notes);
                addStage(stages, start, start, hold);
                double current = start;
                for (int count = 0; current < end - 1e-9; count++) {
                    if (count > 1000) {
                        throw new IllegalArgumentException("A step shape can contain at most 1000 levels.");
                    }
                    double next = Math.min(end, current + increment);
                    addRampStage(stages, current, next, ramp, notes);
                    addStage(stages, next, next, hold);
                    current = next;
                }
            }
            case SPIKE -> {
                double baseline = targetToIterations(config.baselineTarget(), inputs);
                double peak = targetToIterations(config.peakTarget(), inputs);
                long warmup = positiveDuration(config.warmupSeconds(), 60, "Warm-up hold");
                long spike = positiveDuration(config.spikeSeconds(), 60, "Spike duration");
                long recovery = positiveDuration(config.recoverySeconds(), 60, "Recovery hold");
                if (peak <= baseline) {
                    throw new IllegalArgumentException("Spike peak must be greater than its baseline.");
                }
                addStage(stages, 0, baseline, 5);
                addStage(stages, baseline, baseline, warmup);
                addStage(stages, baseline, peak, 5);
                addStage(stages, peak, peak, spike);
                addStage(stages, peak, baseline, 5);
                addStage(stages, baseline, baseline, recovery);
            }
            case STRESS -> {
                double start = targetToIterations(config.startTarget(), inputs);
                double ceiling = targetToIterations(config.endTarget(), inputs);
                double increment = targetToIterations(config.stepTarget(), inputs);
                long interval = positiveDuration(config.stepDurationSeconds(), 120, "Stress step duration");
                if (ceiling < start || increment <= 0) {
                    throw new IllegalArgumentException("Stress ceiling must be at or above its start, with a positive step size.");
                }
                addStage(stages, 0, start, 10);
                double current = start;
                for (int count = 0; current < ceiling - 1e-9; count++) {
                    if (count > 1000) {
                        throw new IllegalArgumentException("A stress shape can contain at most 1000 levels.");
                    }
                    double next = Math.min(ceiling, current + increment);
                    addStage(stages, current, next, interval);
                    current = next;
                }
                if (current == start) {
                    addStage(stages, start, start, interval);
                }
            }
            case CUSTOM -> {
                int maxExpandedStages = config.rampDownSeconds() != null && config.rampDownSeconds() > 0 ? 20 : 21;
                if (config.customStages().isEmpty()) {
                    throw new IllegalArgumentException("Custom shapes need at least 1 stage.");
                }
                if (config.customStages().size() > maxExpandedStages) {
                    int maxTotalStages = maxExpandedStages
                            + (config.rampDownSeconds() != null && config.rampDownSeconds() > 0 ? 1 : 0);
                    throw new IllegalArgumentException("Custom shapes allow at most 10 rows (up to "
                            + maxTotalStages + " stages including ramps).");
                }
                for (WorkloadStage stage : config.customStages()) {
                    addRampStage(stages, stage.fromRate(), stage.toRate(), stage.seconds(), notes);
                }
            }
            default -> throw new IllegalArgumentException("Select a shaped workload.");
        }
        if (config.rampDownSeconds() != null && config.rampDownSeconds() > 0) {
            double lastRate = stages.get(stages.size() - 1).toRate();
            stages.add(new WorkloadStage(lastRate, 0, config.rampDownSeconds()));
        }
        if (stages.stream().noneMatch(stage -> stage.toRate() > 0)) {
            throw new IllegalArgumentException("Enter a positive workload rate.");
        }
        double iterationSeconds = inputs.samplersPerIteration()
                * (responseMillis + thinkMillis) / 1000.0;
        if (!Double.isFinite(iterationSeconds) || iterationSeconds <= 0) {
            throw new IllegalArgumentException("Average response time must be greater than zero.");
        }
        return List.copyOf(stages);
    }

    public static double toIterationsPerSecond(double target, WorkloadInputs.TargetType targetType,
                                               int samplersPerIteration, double messagesPerSample,
                                               long responseMillis, long thinkMillis) {
        if (!Double.isFinite(target) || target < 0 || samplersPerIteration < 1) {
            throw new IllegalArgumentException("Enter a valid workload target.");
        }
        return switch (targetType) {
            case REQUEST_RATE -> target / samplersPerIteration;
            case MESSAGE_RATE -> target / (messagesPerSample * samplersPerIteration);
            case ITERATION_RATE -> target;
            case USERS -> {
                double iterationSeconds = samplersPerIteration * (responseMillis + thinkMillis) / 1000.0;
                if (iterationSeconds <= 0) {
                    throw new IllegalArgumentException("Average response time must be greater than zero.");
                }
                yield target / iterationSeconds;
            }
        };
    }

    private static double targetToIterations(Double target, WorkloadInputs inputs) {
        if (target == null || !Double.isFinite(target) || target < 0) {
            throw new IllegalArgumentException("Enter valid shape rates.");
        }
        return toIterationsPerSecond(target, inputs.targetType(), inputs.samplersPerIteration(),
                inputs.messagesPerSample() == null ? 1 : inputs.messagesPerSample(),
                inputs.responseMillis() == null ? 500 : inputs.responseMillis(),
                inputs.thinkMillis() == null ? 0 : inputs.thinkMillis());
    }

    private static long positiveDuration(Long value, long fallback, String label) {
        long duration = value == null ? fallback : value;
        if (duration <= 0) {
            throw new IllegalArgumentException(label + " must be greater than zero.");
        }
        return duration;
    }

    private static long nonNegativeDuration(Long value, long fallback, String label) {
        long duration = value == null ? fallback : value;
        if (duration < 0) {
            throw new IllegalArgumentException(label + " cannot be negative.");
        }
        return duration;
    }

    private static void addStage(List<WorkloadStage> stages, double from, double to, long seconds) {
        stages.add(new WorkloadStage(from, to, seconds));
    }

    private static void addRampStage(List<WorkloadStage> stages, double from, double to, long seconds,
                                     List<String> notes) {
        if (seconds == 0 && Double.compare(from, to) == 0) {
            return;
        }
        if (seconds == 0) {
            seconds = 1;
            if (!notes.contains(ZERO_RAMP_NOTE)) {
                notes.add(ZERO_RAMP_NOTE);
            }
        }
        addStage(stages, from, to, seconds);
    }

    static String openModelSchedule(List<WorkloadStage> stages, String prefix,
                                    ArrivalMode arrivalMode, int agents) {
        StringBuilder schedule = new StringBuilder();
        int perGeneratorCount = Math.max(1, agents);
        for (int index = 0; index < stages.size(); index++) {
            WorkloadStage stage = stages.get(index);
            if (!schedule.isEmpty()) {
                schedule.append(' ');
            }
            double segmentStartRate = stage.fromRate() / perGeneratorCount;
            schedule.append("rate(${__P(").append(prefix).append(".s").append(index + 1)
                    .append(".rate,").append(formatRate(segmentStartRate))
                    .append(")}/sec) ")
                    .append(arrivalMode.scheduleName()).append("(${__P(")
                    .append(prefix).append(".s").append(index + 1).append(".secs,")
                    .append(stage.seconds()).append(")} sec)");
        }
        WorkloadStage last = stages.get(stages.size() - 1);
        schedule.append(" rate(${__P(").append(prefix).append(".s").append(stages.size() + 1)
                .append(".rate,").append(formatRate(last.toRate() / perGeneratorCount))
                .append(")}/sec)");
        return schedule.toString();
    }

    private static String formatRate(double rate) {
        return BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String openModelCliCommand(String plan, String prefix, List<WorkloadStage> stages,
                                              ArrivalMode arrivalMode, int agents, List<String> hosts,
                                              long heapPerGeneratorMb, CommandShell shell) {
        String heap = heapSize(heapPerGeneratorMb);
        StringBuilder command = new StringBuilder(switch (shell) {
            case BASH -> "export HEAP=\"-Xms" + heap + " -Xmx" + heap + "\"\n";
            case CMD -> "set HEAP=-Xms" + heap + " -Xmx" + heap + "\n";
            case POWERSHELL -> "$env:HEAP = \"-Xms" + heap + " -Xmx" + heap + "\"\n";
        });
        boolean distributed = agents > 1 || !hosts.isEmpty();
        String executable = shell == CommandShell.BASH ? "jmeter" : "jmeter.bat";
        command.append(executable).append(" -n -t ").append(planPath(plan, shell));
        if (distributed) {
            List<String> names = hosts;
            if (names.isEmpty()) {
                names = new ArrayList<>();
                for (int index = 1; index <= agents; index++) {
                    names.add("agent" + index);
                }
            }
            command.append(" -R ").append(shellArgument(String.join(",", names), shell));
        }
        String option = distributed ? "-G" : "-J";
        int perGeneratorCount = distributed ? Math.max(1, agents) : 1;
        for (int index = 0; index < stages.size(); index++) {
            WorkloadStage stage = stages.get(index);
            String stagePrefix = prefix + ".s" + (index + 1);
            command.append(' ').append(shellArgument(option + stagePrefix + ".rate="
                    + formatRate(stage.fromRate() / perGeneratorCount), shell));
            command.append(' ').append(shellArgument(option + stagePrefix + ".secs="
                    + stage.seconds(), shell));
        }
        WorkloadStage last = stages.get(stages.size() - 1);
        command.append(' ').append(shellArgument(option + prefix + ".s" + (stages.size() + 1)
                + ".rate=" + formatRate(last.toRate() / perGeneratorCount), shell));
        return command.append(" -l results.jtl -e -o report").toString();
    }

    static boolean validGeneratorHost(String host) {
        return host != null && VALID_HOST.matcher(host).matches();
    }

    private static String cliCommand(String plan, String prefix, int threads, long ramp, long duration,
                                    Double rpm, long think, int agents, Integer perAgentThreads,
                                    Double perAgentRpm, List<String> hosts, long heapPerGeneratorMb,
                                    CommandShell shell) {
        String heap = heapSize(heapPerGeneratorMb);
        StringBuilder command = new StringBuilder(switch (shell) {
            case BASH -> "export HEAP=\"-Xms" + heap + " -Xmx" + heap + "\"\n";
            case CMD -> "set HEAP=-Xms" + heap + " -Xmx" + heap + "\n";
            case POWERSHELL -> "$env:HEAP = \"-Xms" + heap + " -Xmx" + heap + "\"\n";
        });
        String executable = shell == CommandShell.BASH ? "jmeter" : "jmeter.bat";
        command.append(executable).append(" -n -t ").append(planPath(plan, shell));
        if (agents > 1 || !hosts.isEmpty()) {
            List<String> names = hosts;
            if (names.isEmpty()) {
                names = new ArrayList<>();
                for (int index = 1; index <= agents; index++) {
                    names.add("agent" + index);
                }
            }
            command.append(" -R ").append(shellArgument(String.join(",", names), shell));
            command.append(' ').append(shellArgument("-G" + prefix + ".threads=" + perAgentThreads, shell));
            command.append(' ').append(shellArgument("-G" + prefix + ".rampup=" + ramp, shell));
            command.append(' ').append(shellArgument("-G" + prefix + ".duration=" + duration, shell));
            if (perAgentRpm != null) {
                command.append(' ').append(shellArgument("-G" + prefix + ".rpm=" + format(perAgentRpm), shell));
            }
            if (think > 0) {
                command.append(' ').append(shellArgument("-G" + prefix + ".think_ms=" + think, shell));
            }
        } else {
            command.append(' ').append(shellArgument("-J" + prefix + ".threads=" + threads, shell));
            command.append(' ').append(shellArgument("-J" + prefix + ".rampup=" + ramp, shell));
            command.append(' ').append(shellArgument("-J" + prefix + ".duration=" + duration, shell));
            if (rpm != null) {
                command.append(' ').append(shellArgument("-J" + prefix + ".rpm=" + format(rpm), shell));
            }
            if (think > 0) {
                command.append(' ').append(shellArgument("-J" + prefix + ".think_ms=" + think, shell));
            }
        }
        return command.append(" -l results.jtl -e -o report").toString();
    }

    private static String shellArgument(String argument, CommandShell shell) {
        // Windows command and native-process parsing split unquoted assignments at dots and equals signs.
        return shell == CommandShell.BASH ? argument : "\"" + argument + "\"";
    }

    private static String planPath(String plan, CommandShell shell) {
        return switch (shell) {
            case BASH -> shellQuote(plan);
            case CMD -> SAFE_PLAN_PATH.matcher(plan).matches() ? plan : "\"" + plan + "\"";
            case POWERSHELL -> "'" + plan.replace("'", "''") + "'";
        };
    }

    private static boolean containsUnsafeCmdPlanCharacter(String plan) {
        return plan.chars().anyMatch(character -> UNSAFE_CMD_PLAN_CHARACTERS.indexOf(character) >= 0);
    }

    private static String shellQuote(String value) {
        return SAFE_PLAN_PATH.matcher(value).matches() ? value : "'" + value.replace("'", "'\\''") + "'";
    }

    private static long ceilTo512(long value) {
        return ((value + 511) / 512) * 512;
    }

    private static long floorTo512(double value) {
        return (long) Math.floor(value / 512) * 512;
    }

    private static int ceilCount(double numerator, double denominator) {
        double count = Math.ceil(numerator / denominator);
        if (!Double.isFinite(count) || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The calculated generator count is too large.");
        }
        return (int) count;
    }

    private static String heapSize(long megabytes) {
        return megabytes % 1024 == 0 ? megabytes / 1024 + "g" : megabytes + "m";
    }

    private static List<String> protocolWarnings(List<JdbcPool> pools, SamplerMix mix,
                                                 int threadsPerGenerator) {
        List<String> warnings = new ArrayList<>();
        if (mix.has(SamplerMix.Kind.JDBC)) {
            for (JdbcPool pool : pools) {
                if (pool.poolMax() > 0 && pool.poolMax() < threadsPerGenerator) {
                    warnings.add("JDBC pool \"" + pool.variableName() + "\" allows " + pool.poolMax()
                            + " connections but " + threadsPerGenerator + " threads share it; threads will queue and the target may not be reached. "
                            + "Set Max Number of Connections to 0 (one connection per thread) or at least "
                            + threadsPerGenerator + ".");
                }
            }
        }
        return warnings;
    }

    private static List<String> protocolNotes(SamplerMix mix) {
        List<String> notes = new ArrayList<>();
        if (mix.has(SamplerMix.Kind.KAFKA)) {
            notes.add("Kafka samples may carry multiple messages. Use Messages per second; producers usually expect an open-model fixed rate, which the Constant Throughput Timer only approximates.");
        }
        if (mix.has(SamplerMix.Kind.JMS)) {
            notes.add("JMS samples may carry multiple messages. Use Messages per second; producers usually expect an open-model fixed rate, which the Constant Throughput Timer only approximates.");
        }
        if (mix.has(SamplerMix.Kind.SMTP)) {
            notes.add("Mail servers may enforce send-rate limits. Test against a sandbox SMTP server.");
        }
        return notes;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
