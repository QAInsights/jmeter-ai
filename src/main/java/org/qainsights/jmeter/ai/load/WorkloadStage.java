package org.qainsights.jmeter.ai.load;

/** A piecewise arrival-rate segment. Rates are iterations per second. */
public record WorkloadStage(double fromRate, double toRate, long seconds) {
    public WorkloadStage {
        if (!Double.isFinite(fromRate) || fromRate < 0
                || !Double.isFinite(toRate) || toRate < 0 || seconds <= 0) {
            throw new IllegalArgumentException("Workload stages require non-negative rates and a positive duration.");
        }
    }
}
