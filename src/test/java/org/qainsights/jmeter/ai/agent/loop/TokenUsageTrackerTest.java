package org.qainsights.jmeter.ai.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;

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
                reply -> new TokenUsageTracker.Counts(10L, 0L, null, 5L, 2L), Function.identity());
        assertThrows(IllegalStateException.class, () -> tracker.track(
                new TokenUsageTracker.RequestSize(2, 35, 200), () -> { throw new IllegalStateException(); },
                reply -> TokenUsageTracker.Counts.UNREPORTED, Function.identity()));
        String summary = tracker.summary();
        assertTrue(summary.contains("1 | 10 | 0 | - | 5 | 2 | 1 | 20 | 120"), summary);
        assertTrue(summary.contains("2 | - | - | - | - | - | 2 | 35 | 200"), summary);
        assertTrue(summary.contains("total | 10 | 0 | - | 5 | 2 | 3 | 55 | 320"), summary);
    }

    @Test
    void runningTotalsIncludeCachedInputAndSkipUnreportedTurns() {
        TokenUsageTracker tracker = new TokenUsageTracker("example");
        tracker.track(new TokenUsageTracker.RequestSize(1, 20, 120), () -> "reply",
                reply -> new TokenUsageTracker.Counts(10L, 3L, 2L, 5L, 2L), Function.identity());
        assertEquals(15, tracker.promptTokens());
        assertEquals(5, tracker.completionTokens());
        assertEquals(20, tracker.totalTokens());

        tracker.track(new TokenUsageTracker.RequestSize(0, 0, 120), () -> "reply",
                reply -> TokenUsageTracker.Counts.UNREPORTED, Function.identity());
        assertEquals(1, tracker.unreportedTurns());
        assertEquals(20, tracker.totalTokens());
    }

    @Test
    void distinguishesFailedCallsFromSuccessfulCallsWithoutUsage() {
        TokenUsageTracker tracker = new TokenUsageTracker("example");
        var request = new TokenUsageTracker.RequestSize(1, 20, 120);
        var failure = new IllegalStateException("private diagnostic");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> tracker.track(
                request, () -> { throw failure; }, reply -> TokenUsageTracker.Counts.UNREPORTED, Function.identity())));
        tracker.track(request, () -> "reply", reply -> TokenUsageTracker.Counts.UNREPORTED, Function.identity());

        assertEquals(1, tracker.unreportedTurns());
        String summary = tracker.summary();
        assertTrue(summary.contains("request chars | status"), summary);
        assertTrue(summary.contains("1 | - | - | - | - | - | 1 | 20 | 120 | failed"), summary);
        assertTrue(summary.contains("2 | - | - | - | - | - | 1 | 20 | 120 | ok"), summary);
        assertFalse(summary.contains(failure.getMessage()), summary);
    }
}
