package org.qainsights.jmeter.ai.fix;

import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.utils.AiConfig;

/** Configuration for one Make it pass workflow. */
public final class FixRequest {
    public static final String MAX_ROUNDS_KEY = "jmeter.ai.fix.max.rounds";
    public static final String TIMEOUT_SECONDS_KEY = "jmeter.ai.fix.timeout.seconds";
    public static final String CONCURRENCY_CHECK_KEY = "jmeter.ai.fix.concurrency.check";

    private final int maxRounds;
    private final long timeoutSeconds;
    private final boolean concurrencyCheck;

    public FixRequest(int maxRounds, long timeoutSeconds) {
        this(maxRounds, timeoutSeconds, false);
    }

    public FixRequest(int maxRounds, long timeoutSeconds, boolean concurrencyCheck) {
        this.maxRounds = clamp(maxRounds, 1, 10);
        this.timeoutSeconds = clamp(timeoutSeconds, 10, 900);
        this.concurrencyCheck = concurrencyCheck;
    }

    public int maxRounds() {
        return maxRounds;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean concurrencyCheck() {
        return concurrencyCheck;
    }

    public static FixRequest fromConfig() {
        long rounds = JMeterAgent.parseLong(AiConfig.getProperty(MAX_ROUNDS_KEY, "5"), 5);
        long timeout = JMeterAgent.parseLong(AiConfig.getProperty(TIMEOUT_SECONDS_KEY, "120"), 120);
        boolean concurrency = Boolean.parseBoolean(AiConfig.getProperty(CONCURRENCY_CHECK_KEY, "true"));
        return new FixRequest((int) clamp(rounds, 1, 10), clamp(timeout, 10, 900), concurrency);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
