package org.qainsights.jmeter.ai.fix;

import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.utils.AiConfig;

/** Configuration for one Make it pass workflow. */
public final class FixRequest {
    public static final String MAX_ROUNDS_KEY = "jmeter.ai.fix.max.rounds";
    public static final String TIMEOUT_SECONDS_KEY = "jmeter.ai.fix.timeout.seconds";

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

    public static FixRequest fromConfig() {
        long rounds = JMeterAgent.parseLong(AiConfig.getProperty(MAX_ROUNDS_KEY, "5"), 5);
        long timeout = JMeterAgent.parseLong(AiConfig.getProperty(TIMEOUT_SECONDS_KEY, "120"), 120);
        return new FixRequest((int) clamp(rounds, 1, 10), clamp(timeout, 10, 900));
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
