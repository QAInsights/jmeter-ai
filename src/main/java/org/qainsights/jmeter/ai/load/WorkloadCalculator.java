package org.qainsights.jmeter.ai.load;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.qainsights.jmeter.ai.load.WorkloadInputs.TargetType.ITERATION_RATE;
import static org.qainsights.jmeter.ai.load.WorkloadInputs.TargetType.REQUEST_RATE;
import static org.qainsights.jmeter.ai.load.WorkloadInputs.TargetType.USERS;

/** Deterministic Little's Law calculator for closed-model JMeter workloads. */
public final class WorkloadCalculator {
    private static final String RESPONSE_ASSUMPTION =
            "Assumed 500 ms average response time. Measure it with a 1-user run (for example `@fix`) and update it.";

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
        int agents = inputs.agents() == null ? 1 : inputs.agents();
        String prefix = inputs.propertyPrefix() == null || inputs.propertyPrefix().isBlank()
                ? "load" : inputs.propertyPrefix();
        String planFile = inputs.planFileName() == null || inputs.planFileName().isBlank()
                ? "plan.jmx" : inputs.planFileName();
        double secondsPerRequest = (responseMillis + thinkMillis) / 1000.0;
        double rps = switch (inputs.targetType()) {
            case REQUEST_RATE -> inputs.targetValue();
            case ITERATION_RATE -> inputs.targetValue() * inputs.samplersPerIteration();
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
                            + " requests/s at " + responseMillis + " ms per request (plus think time);"
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
        Integer perAgentThreads = agents > 1 ? (int) Math.ceil(threads / (double) agents) : null;
        Double perAgentRpm = agents > 1 && rpm != null ? round2(rpm / agents) : null;
        if (threads > 1000) {
            warnings.add("Above 1,000 threads, watch the load generator's CPU and heap, and consider splitting across agents.");
        }
        int rampThreads = perAgentThreads == null ? threads : perAgentThreads;
        if (rampSeconds == 0 || rampThreads / (double) rampSeconds > 50) {
            warnings.add("Steep ramp-up: more than 50 threads start per second, which can overload the generator and skew early results.");
        }
        String command = cliCommand(planFile, prefix, threads, rampSeconds, durationSeconds, rpm,
                thinkMillis, agents, perAgentThreads, perAgentRpm);
        return new WorkloadPlan(inputs.targetType(), inputs.targetValue(), threads,
                inputs.samplersPerIteration(), targetRps, expectedRps, rpm, rampSeconds, holdSeconds,
                durationSeconds, agents, perAgentThreads, perAgentRpm, responseMillis, thinkMillis,
                prefix, assumptions, warnings, command);
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
            throw new IllegalArgumentException("Enter a positive requests-per-second cap.");
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
    }

    private static String cliCommand(String plan, String prefix, int threads, long ramp, long duration,
                                    Double rpm, long think, int agents, Integer perAgentThreads,
                                    Double perAgentRpm) {
        StringBuilder command = new StringBuilder("jmeter -n -t ").append(plan);
        if (agents > 1) {
            List<String> names = new ArrayList<>();
            for (int index = 1; index <= agents; index++) {
                names.add("agent" + index);
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
