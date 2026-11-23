package org.qainsights.jmeter.ai.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void preservesRequestSizesForFailedCallsAndDistinguishesUnknownCounts() {
        TokenUsageTracker tracker = new TokenUsageTracker("example");
        tracker.track(new TokenUsageTracker.RequestSize(1, 20, 120), () -> "reply",
                reply -> new TokenUsageTracker.Counts(10L, 0L, null, 5L, 2L));
        assertThrows(IllegalStateException.class, () -> tracker.track(
                new TokenUsageTracker.RequestSize(2, 35, 200), () -> { throw new IllegalStateException(); },
                reply -> TokenUsageTracker.Counts.UNREPORTED));
        String summary = tracker.summary();
        assertTrue(summary.contains("1 | 10 | 0 | - | 5 | 2 | 1 | 20 | 120"), summary);
        assertTrue(summary.contains("2 | - | - | - | - | - | 2 | 35 | 200"), summary);
        assertTrue(summary.contains("total | 10 | 0 | - | 5 | 2 | 3 | 55 | 320"), summary);
    }

    @Test
    void runningTotalsIncludeCachedInputAndSkipUnreportedTurns() {
        TokenUsageTracker tracker = new TokenUsageTracker("example");
        tracker.track(new TokenUsageTracker.RequestSize(1, 20, 120), () -> "reply",
                reply -> new TokenUsageTracker.Counts(10L, 3L, 2L, 5L, 2L));
        assertEquals(15, tracker.promptTokens());
        assertEquals(5, tracker.completionTokens());
        assertEquals(20, tracker.totalTokens());

        tracker.track(new TokenUsageTracker.RequestSize(0, 0, 120), () -> "reply",
                reply -> TokenUsageTracker.Counts.UNREPORTED);
        assertEquals(1, tracker.unreportedTurns());
        assertEquals(20, tracker.totalTokens());
    }
}
