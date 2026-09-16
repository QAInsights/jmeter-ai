package org.qainsights.jmeter.ai.agent.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accumulates prompt/completion token counts reported by the provider across the
 * turns of one agent run and logs each turn plus the running total, so users on
 * quota-limited gateways can see what a request actually costs.
 */
public final class TokenUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageTracker.class);

    private final String provider;
    private int turns;
    private int unreportedTurns;
    private long promptTokens;
    private long completionTokens;

    public TokenUsageTracker(String provider) {
        this.provider = provider;
    }

    public void record(long prompt, long completion) {
        turns++;
        promptTokens += prompt;
        completionTokens += completion;
        log.info("Agent token usage [{}] turn {}: prompt={} completion={} | run total: prompt={} completion={} total={}",
                provider, turns, prompt, completion, promptTokens, completionTokens, totalTokens());
    }

    /** Records a turn whose response carried no usage block (some gateways strip it). */
    public void recordUnreported() {
        turns++;
        unreportedTurns++;
        log.info("Agent token usage [{}] turn {}: not reported by provider | run total: prompt={} completion={} total={} ({} turn(s) unreported)",
                provider, turns, promptTokens, completionTokens, totalTokens(), unreportedTurns);
    }

    public int unreportedTurns() {
        return unreportedTurns;
    }

    public int turns() {
        return turns;
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
