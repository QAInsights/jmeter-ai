package org.qainsights.jmeter.ai.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.loop.TokenUsageTracker;
import org.qainsights.jmeter.ai.service.usage.UsageStats;

class TokenUsageRecordingTest {
    private TelemetryTokenCounters tokenCounters;
    private TelemetryTokenCounters previous;

    @BeforeEach
    void setUp() {
        tokenCounters = new TelemetryTokenCounters();
        previous = Telemetry.swapTokenCountersForTesting(tokenCounters);
    }

    @AfterEach
    void tearDown() {
        Telemetry.swapTokenCountersForTesting(previous);
    }

    @Test
    void chatUsageStatsFeedTheAggregateTelemetryCounters() {
        new UsageStats().record("openai:gpt-5.1", 1000, 200);

        assertEquals(1000, tokenCounters.snapshot().inputTokens());
        assertEquals(200, tokenCounters.snapshot().outputTokens());
    }

    @Test
    void agentUsageIncludesCachedInputAndOutput() {
        TokenUsageTracker tracker = new TokenUsageTracker("test");
        tracker.track(new TokenUsageTracker.RequestSize(1, 10, 100), () -> "reply",
                reply -> new TokenUsageTracker.Counts(10L, 3L, 2L, 5L, 1L), Function.identity());

        assertEquals(15, tokenCounters.snapshot().inputTokens());
        assertEquals(5, tokenCounters.snapshot().outputTokens());
    }
}
