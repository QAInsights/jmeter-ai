package org.qainsights.jmeter.ai.load;

import java.math.BigDecimal;
import java.util.Locale;

public final class WorkloadLimits {
    public static final double MIN_RATE = 0.001;
    public static final double MAX_RATE = 1_000_000;
    public static final int MAX_USERS = 1_000_000;
    public static final int MAX_THREADS = 1_000_000;
    public static final long MAX_MILLIS = 3_600_000;
    public static final long MAX_DURATION_SECONDS = 604_800;
    public static final int MAX_LEVELS = 100;
    public static final int MAX_GENERATORS = 1_000;
    public static final int MAX_THREADS_PER_GENERATOR = 100_000;
    public static final int MAX_VCPUS = 1_024;
    public static final int MAX_RAM_GB = 4_096;
    public static final int MAX_SAMPLES_PER_VCPU = 1_000_000;
    public static final int MAX_MESSAGES_PER_SAMPLE = 100_000;
    public static final int MAX_PREFIX_LENGTH = 64;
    public static final int MAX_HOST_LENGTH = 253;

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

    public static double minTarget(WorkloadInputs.TargetType type) {
        return type == WorkloadInputs.TargetType.USERS ? 1 : MIN_RATE;
    }

    public static String minTargetMessage(String label, WorkloadInputs.TargetType type) {
        String minimum = type == WorkloadInputs.TargetType.USERS
                ? "1" : BigDecimal.valueOf(MIN_RATE).stripTrailingZeros().toPlainString();
        return label + " must be at least " + minimum + " " + targetUnit(type) + ".";
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

    public static String totalDurationLimitMessage(long durationSeconds) {
        return "Total test length can be at most 7 days (this plan is "
                + formatDuration(durationSeconds) + ").";
    }

    public static String aboveStartMessage(String label, String startLabel, double startTarget) {
        return label + " must be above " + startLabel + " (" + formatTarget(startTarget) + ").";
    }

    public static String maximumStepMessage(String label, String differenceLabel, double difference) {
        return label + " can be at most " + formatTarget(difference)
                + " (" + differenceLabel + ").";
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

    private static String formatTarget(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        String plain = decimal.toPlainString();
        int point = plain.indexOf('.');
        String whole = point < 0 ? plain : plain.substring(0, point);
        String formattedWhole = String.format(Locale.US, "%,d", Long.parseLong(whole));
        return point < 0 ? formattedWhole : formattedWhole + plain.substring(point);
    }

    private static String formatDuration(long seconds) {
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainingSeconds = seconds % 60;
        if (days > 0) {
            return days + " d" + (hours > 0 ? " " + hours + " h"
                    : minutes > 0 ? " " + minutes + " min" : "");
        }
        if (hours > 0) {
            return hours + " h" + (minutes > 0 ? " " + minutes + " min" : "");
        }
        return minutes + " min" + (remainingSeconds > 0 ? " " + remainingSeconds + " s" : "");
    }

    private static String formatCount(double value) {
        if (!Double.isFinite(value) || value > 1_000_000_000d) {
            return ">1,000,000,000";
        }
        return String.format(Locale.US, "%,.0f", value);
    }
}
