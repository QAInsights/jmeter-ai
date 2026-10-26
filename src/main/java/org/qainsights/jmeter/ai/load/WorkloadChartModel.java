package org.qainsights.jmeter.ai.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure workload-series and time-axis calculations for the load-shape chart. */
public final class WorkloadChartModel {
    private static final long[] TICK_STEPS_SECONDS = {
            10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200
    };

    private WorkloadChartModel() {
    }

    public static Series at(WorkloadPlan plan, double elapsedSeconds) {
        double elapsed = Math.max(0, Math.min(plan.durationSeconds(), elapsedSeconds));
        double ramp = plan.rampSeconds();
        double threadFraction = ramp <= 0 ? 1 : Math.min(1, elapsed / ramp);
        double threads = plan.threads() * threadFraction;
        double cycleSeconds = (plan.responseMillis() + plan.thinkMillis()) / 1000.0;
        double requestsPerSecond = threads / cycleSeconds;
        Double cap = plan.targetRequestsPerSecond();
        if (cap != null) {
            requestsPerSecond = Math.min(requestsPerSecond, cap);
        }
        return new Series(elapsed, threads, requestsPerSecond);
    }

    public static double ratePlateauSeconds(WorkloadPlan plan) {
        double ramp = plan.rampSeconds();
        if (ramp <= 0 || plan.targetRequestsPerSecond() == null) {
            return ramp;
        }
        double cycleSeconds = (plan.responseMillis() + plan.thinkMillis()) / 1000.0;
        double threadsAtCap = plan.targetRequestsPerSecond() * cycleSeconds;
        return ramp * Math.min(1, threadsAtCap / plan.threads());
    }

    public static double maximumRequestsPerSecond(WorkloadPlan plan) {
        return at(plan, plan.durationSeconds()).requestsPerSecond();
    }

    public static double requestRateAxisMaximum(WorkloadPlan plan) {
        Double cap = plan.targetRequestsPerSecond();
        return cap == null ? maximumRequestsPerSecond(plan) : cap;
    }

    public static long tickStepSeconds(long totalSeconds) {
        long bestStep = TICK_STEPS_SECONDS[0];
        double bestDistance = Double.POSITIVE_INFINITY;
        for (long step : TICK_STEPS_SECONDS) {
            double tickCount = Math.ceil(totalSeconds / (double) step);
            double distance = Math.abs(tickCount - 5);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStep = step;
            }
        }
        return bestStep;
    }

    public static List<Long> timeTicks(long totalSeconds) {
        if (totalSeconds <= 0) {
            return List.of(0L);
        }
        long step = tickStepSeconds(totalSeconds);
        List<Long> ticks = new ArrayList<>();
        for (long tick = 0; tick < totalSeconds; ) {
            ticks.add(tick);
            if (totalSeconds - tick < step) {
                break;
            }
            tick += step;
        }
        if (totalSeconds % step == 0) {
            ticks.add(totalSeconds);
        }
        return List.copyOf(ticks);
    }

    public static String formatTime(long seconds, long totalSeconds) {
        if (totalSeconds >= 3600) {
            long hours = seconds / 3600;
            long minutes = seconds % 3600 / 60;
            return String.format(Locale.ROOT, "%d:%02d", hours, minutes);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    public record Series(double elapsedSeconds, double threads, double requestsPerSecond) {
    }
}
