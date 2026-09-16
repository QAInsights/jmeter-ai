package org.qainsights.jmeter.ai.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenUsageTrackerTest {

    @Test
    void accumulatesAcrossTurns() {
        TokenUsageTracker tracker = new TokenUsageTracker("test");
        tracker.record(4000, 120);
        tracker.recordUnreported();
        tracker.record(5500, 80);

        assertEquals(3, tracker.turns());
        assertEquals(1, tracker.unreportedTurns());
        assertEquals(9500, tracker.promptTokens());
        assertEquals(200, tracker.completionTokens());
        assertEquals(9700, tracker.totalTokens());
    }
}
