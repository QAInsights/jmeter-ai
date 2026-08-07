package org.qainsights.jmeter.ai.service.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UsageStats}: accumulation, per-model cost pricing
 * against the vendored catalog, unknown-pricing handling, and reset.
 */
class UsageStatsTest {

    @Test
    void startsEmpty() {
        UsageStats stats = new UsageStats();
        UsageStats.Snapshot snap = stats.snapshot();
        assertEquals(0, snap.calls());
        assertEquals(0, snap.totalTokens());
        assertFalse(snap.costKnown());
        assertEquals(-1, stats.lastInputTokens());
    }

    @Test
    void accumulatesTokensAcrossCalls() {
        UsageStats stats = new UsageStats();
        stats.record("openai:gpt-5.1", 1000, 200);
        stats.record("openai:gpt-5.1", 500, 100);

        UsageStats.Snapshot snap = stats.snapshot();
        assertEquals(2, snap.calls());
        assertEquals(1500, snap.totalInput());
        assertEquals(300, snap.totalOutput());
        assertEquals(500, stats.lastInputTokens());
    }

    @Test
    void pricesEachCallAtCatalogListPrice() {
        UsageStats stats = new UsageStats();
        // gpt-5.1: $1.25/Mtok in, $10/Mtok out in the vendored catalog
        stats.record("openai:gpt-5.1", 1_000_000, 100_000);

        UsageStats.Snapshot snap = stats.snapshot();
        assertTrue(snap.costKnown());
        assertEquals(1.25 + 1.00, snap.costUsd(), 0.0001);
    }

    @Test
    void mixedModelsPriceIndependently() {
        UsageStats stats = new UsageStats();
        stats.record("openai:gpt-4o", 1_000_000, 0);   // $2.50
        stats.record("claude-sonnet-4-6", 0, 100_000); // $15/Mtok out = $1.50

        assertEquals(4.00, stats.snapshot().costUsd(), 0.0001);
    }

    @Test
    void unknownModelContributesTokensButNoCost() {
        UsageStats stats = new UsageStats();
        stats.record("ollama:my-local-thing", 1000, 500);

        UsageStats.Snapshot snap = stats.snapshot();
        assertEquals(1, snap.calls());
        assertEquals(1500, snap.totalTokens());
        assertFalse(snap.costKnown());
        assertEquals(0, snap.costUsd(), 0.0001);
    }

    @Test
    void negativeCountsAreIgnored() {
        UsageStats stats = new UsageStats();
        stats.record("openai:gpt-5.1", -1, -1);
        assertEquals(0, stats.snapshot().calls());
    }

    @Test
    void zeroZeroPairIsIgnored() {
        UsageStats stats = new UsageStats();
        stats.record("google:gemini-2.5-pro", 0, 0);
        assertEquals(0, stats.snapshot().calls());
        assertEquals(-1, stats.lastInputTokens());
    }

    @Test
    void resetClearsEverything() {
        UsageStats stats = new UsageStats();
        stats.record("openai:gpt-5.1", 1000, 200);
        stats.reset();

        UsageStats.Snapshot snap = stats.snapshot();
        assertEquals(0, snap.calls());
        assertEquals(0, snap.totalTokens());
        assertFalse(snap.costKnown());
        assertEquals(-1, stats.lastInputTokens());
    }
}
