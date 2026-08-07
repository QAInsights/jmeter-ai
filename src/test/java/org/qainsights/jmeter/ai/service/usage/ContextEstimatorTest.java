package org.qainsights.jmeter.ai.service.usage;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContextEstimator}: chars/4 heuristic, overhead
 * allowance, null/empty handling.
 */
class ContextEstimatorTest {

    @Test
    void emptyHistoryEstimatesZero() {
        assertEquals(0, ContextEstimator.estimateTokens(List.of()));
        assertEquals(0, ContextEstimator.estimateTokens(null));
    }

    @Test
    void historyCharsDriveTheEstimate() {
        // 4000 chars / 4 = 1000 tokens + overhead
        List<String> history = List.of("x".repeat(2000), "y".repeat(2000));
        assertEquals(1000 + ContextEstimator.OVERHEAD_TOKENS,
                ContextEstimator.estimateTokens(history));
    }

    @Test
    void nullTurnsAreSkipped() {
        List<String> history = java.util.Arrays.asList("abcd", null);
        assertEquals(1 + ContextEstimator.OVERHEAD_TOKENS,
                ContextEstimator.estimateTokens(history));
    }
}
