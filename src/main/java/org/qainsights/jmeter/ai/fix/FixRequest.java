package org.qainsights.jmeter.ai.fix;

/** Configuration for one Make it pass workflow. */
public final class FixRequest {
    private final int maxRounds;
    private final long timeoutSeconds;

    public FixRequest(int maxRounds, long timeoutSeconds) {
        this.maxRounds = clamp(maxRounds, 1, 10);
        this.timeoutSeconds = clamp(timeoutSeconds, 10, 900);
    }

    public int maxRounds() {
        return maxRounds;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
