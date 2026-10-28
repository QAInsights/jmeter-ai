package org.qainsights.jmeter.ai.load;

import java.util.Locale;

public final class WorkloadLimits {
    public static final double MAX_RATE = 1_000_000;
    public static final int MAX_USERS = 1_000_000;
    public static final int MAX_THREADS = 1_000_000;
    public static final long MAX_MILLIS = 3_600_000;
    public static final long MAX_DURATION_SECONDS = 604_800;
    public static final int MAX_GENERATORS = 1_000;
    public static final int MAX_THREADS_PER_GENERATOR = 100_000;
    public static final int MAX_VCPUS = 1_024;
    public static final int MAX_RAM_GB = 4_096;
    public static final int MAX_SAMPLES_PER_VCPU = 1_000_000;
    public static final int MAX_MESSAGES_PER_SAMPLE = 100_000;

    private WorkloadLimits() {
    }

    public static String targetUnit(WorkloadInputs.TargetType type) {
        if (type == null) {
            return "samples/s";
        }
        return switch (type) {
            case REQUEST_RATE -> "samples/s";
            case ITERATION_RATE -> "iterations/s";
            case MESSAGE_RATE -> "messages/s";
            case USERS -> "users";
        };
    }

    public static long maxTarget(WorkloadInputs.TargetType type) {
        return type == WorkloadInputs.TargetType.USERS ? MAX_USERS : (long) MAX_RATE;
    }

    public static String maxMessage(String label, long limit, String unit) {
        return label + " can be at most " + format(limit)
                + (unit == null || unit.isBlank() ? "" : " " + unit) + ".";
    }

    public static String targetLimitMessage(String label, WorkloadInputs.TargetType type) {
        return maxMessage(label, maxTarget(type), targetUnit(type));
    }

    public static String durationLimitMessage(String label) {
        return label + " can be at most 7 days.";
    }

    public static String millisLimitMessage(String label) {
        return maxMessage(label, MAX_MILLIS, "ms (1 h)");
    }

    public static String generatorsNeededMessage(double count) {
        return "This workload needs " + formatCount(count)
                + " load generators, above the " + format(MAX_GENERATORS) + " limit.";
    }

    public static String threadCountLimitMessage(double count) {
        return "This target needs about " + formatCount(count)
                + " threads, above the " + format(MAX_THREADS)
                + " limit. Lower the target value, response time, or think time.";
    }

    public static String format(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String formatCount(double value) {
        if (!Double.isFinite(value) || value > 1_000_000_000d) {
            return ">1,000,000,000";
        }
        return String.format(Locale.US, "%,.0f", value);
    }
}
