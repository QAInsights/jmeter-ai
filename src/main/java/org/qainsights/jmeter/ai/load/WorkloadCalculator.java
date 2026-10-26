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
    private static final String RESPONSE_ASSUMPTION =
            "Assumed 500 ms average response time. Measure it with a 1-user run (for example `@fix`) and update it.";
    private static final Pattern SAFE_PLAN_PATH = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Pattern VALID_HOST = Pattern.compile("[A-Za-z0-9._:-]+");

    private WorkloadCalculator() {
    }

    public static WorkloadPlan calculate(WorkloadInputs inputs) {
        validate(inputs);
        List<String> assumptions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long responseMillis = inputs.responseMillis() == null ? 500 : inputs.responseMillis();
        long thinkMillis = inputs.thinkMillis() == null ? 0 : inputs.thinkMillis();
        long holdSeconds = inputs.holdSeconds() == null ? 600 : inputs.holdSeconds();
        if (inputs.responseMillis() == null) {
            assumptions.add(RESPONSE_ASSUMPTION);
        }
        if (inputs.holdSeconds() == null) {
            assumptions.add("Assumed a 600-second (10-minute) hold.");
        }
        long rampSeconds = inputs.rampSeconds() == null
                ? clamp(Math.round(holdSeconds * 0.1), 60, 900)
                : inputs.rampSeconds();
        if (inputs.rampSeconds() == null) {
            assumptions.add("Assumed a " + rampSeconds
                    + "-second ramp-up (10% of the hold, clamped from 60 to 900 seconds).");
        }
        List<String> hosts = inputs.generatorHosts();
        int agents = hosts.isEmpty() ? (inputs.agents() == null ? 1 : inputs.agents()) : hosts.size();
        int maxThreads = inputs.maxThreadsPerGenerator() == null ? 1000 : inputs.maxThreadsPerGenerator();
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
        Double targetRps = inputs.targetType() == USERS
                ? inputs.rateCapRps() : Double.valueOf(rps);
        Double rpm = null;
        Double expectedRps = null;
        int threads;

        if (inputs.targetType() == USERS) {
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
        long durationSeconds = Math.addExact(rampSeconds, holdSeconds);
        boolean distributed = agents > 1 || !hosts.isEmpty();
        Integer perAgentThreads = distributed ? (int) Math.ceil(threads / (double) agents) : null;
        Double perAgentRpm = distributed && rpm != null ? round2(rpm / agents) : null;
        int rampThreads = perAgentThreads == null ? threads : perAgentThreads;
        if (rampSeconds == 0 || rampThreads / (double) rampSeconds > 50) {
            warnings.add("Steep ramp-up: more than 50 threads start per second, which can overload the generator and skew early results.");
        }
        int generatorsNeeded = (int) Math.ceil(threads / (double) maxThreads);
        if (agents < generatorsNeeded) {
            warnings.add(threads + " threads need at least " + generatorsNeeded + " generators at "
                    + maxThreads + " threads each.");
        }
        long threadsPerGenerator = perAgentThreads == null ? threads : perAgentThreads;
        long heapPerGeneratorMb = ceilTo512(Math.max(1024, 512 + 2 * threadsPerGenerator));
        SamplerMix samplerMix = SamplerMix.classify(inputs.samplerClasses());
        warnings.addAll(protocolWarnings(inputs.jdbcPools(), samplerMix, (int) threadsPerGenerator));
        List<String> notes = protocolNotes(samplerMix);
        if (inputs.targetType() == ITERATION_RATE) {
            notes.add("Samples per iteration counts each enabled sampler once; If, Loop, Throughput and Transaction (parent sample) controllers change the real number.");
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
        String command = cliCommand(planFile, prefix, threads, rampSeconds, durationSeconds, rpm,
                thinkMillis, agents, perAgentThreads, perAgentRpm, hosts, heapPerGeneratorMb);
        return new WorkloadPlan(inputs.targetType(), inputs.targetValue(), threads,
                inputs.samplersPerIteration(), targetRps, expectedRps, rpm, rampSeconds, holdSeconds,
                durationSeconds, agents, perAgentThreads, perAgentRpm, responseMillis, thinkMillis,
                prefix, assumptions, warnings, command, samplerMix, notes, hosts, maxThreads,
                generatorsNeeded, heapPerGeneratorMb);
    }

    private static void validate(WorkloadInputs inputs) {
        if (inputs == null || inputs.targetType() == null || !Double.isFinite(inputs.targetValue())
                || inputs.targetValue() <= 0) {
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
        for (String host : inputs.generatorHosts()) {
            if (host == null || !VALID_HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("Enter generator hosts using letters, numbers, dots, underscores, colons, or hyphens.");
            }
        }
    }

    private static String cliCommand(String plan, String prefix, int threads, long ramp, long duration,
                                    Double rpm, long think, int agents, Integer perAgentThreads,
                                    Double perAgentRpm, List<String> hosts, long heapPerGeneratorMb) {
        String heap = heapSize(heapPerGeneratorMb);
        StringBuilder command = new StringBuilder("export HEAP=\"-Xms").append(heap)
                .append(" -Xmx").append(heap).append("\"\n");
        command.append("jmeter -n -t ").append(shellQuote(plan));
        if (agents > 1 || !hosts.isEmpty()) {
            List<String> names = hosts;
            if (names.isEmpty()) {
                names = new ArrayList<>();
                for (int index = 1; index <= agents; index++) {
                    names.add("agent" + index);
                }
            }
            command.append(" -R ").append(String.join(",", names));
            command.append(" -G").append(prefix).append(".threads=").append(perAgentThreads);
            command.append(" -G").append(prefix).append(".rampup=").append(ramp);
            command.append(" -G").append(prefix).append(".duration=").append(duration);
            if (perAgentRpm != null) {
                command.append(" -G").append(prefix).append(".rpm=").append(format(perAgentRpm));
            }
            if (think > 0) {
                command.append(" -G").append(prefix).append(".think_ms=").append(think);
            }
        } else {
            command.append(" -J").append(prefix).append(".threads=").append(threads);
            command.append(" -J").append(prefix).append(".rampup=").append(ramp);
            command.append(" -J").append(prefix).append(".duration=").append(duration);
            if (rpm != null) {
                command.append(" -J").append(prefix).append(".rpm=").append(format(rpm));
            }
            if (think > 0) {
                command.append(" -J").append(prefix).append(".think_ms=").append(think);
            }
        }
        return command.append(" -l results.jtl -e -o report").toString();
    }

    private static String shellQuote(String value) {
        return SAFE_PLAN_PATH.matcher(value).matches() ? value : "'" + value.replace("'", "'\\''") + "'";
    }

    private static long ceilTo512(long value) {
        return ((value + 511) / 512) * 512;
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
