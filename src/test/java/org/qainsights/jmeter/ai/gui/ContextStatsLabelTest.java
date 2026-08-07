package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.usage.UsageStats;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContextStatsLabel}: text formatting, estimate marker,
 * hidden denominator/cost for unknown data, tooltip breakdown.
 */
class ContextStatsLabelTest {

    private static UsageStats.Snapshot stats(long in, long out, double cost, boolean costKnown, int calls) {
        return new UsageStats.Snapshot(in, out, cost, costKnown, in, calls);
    }

    @Test
    void blankBeforeAnyActivity() {
        ContextStatsLabel label = new ContextStatsLabel();
        label.showStats(0, true, 400_000, stats(0, 0, 0, false, 0));
        assertEquals("", label.getText());
        assertNull(label.getToolTipText());
    }

    @Test
    void fullRenderingWithWindowAndCost() {
        ContextStatsLabel label = new ContextStatsLabel();
        label.showStats(12_345, false, 400_000, stats(45_210, 3_122, 0.0412, true, 2));
        assertEquals("ctx 12.3k/400k · $0.04", label.getText());

        String tip = label.getToolTipText();
        assertTrue(tip.contains("12,345 / 400,000"));
        assertTrue(tip.contains("45,210 in · 3,122 out"));
        assertTrue(tip.contains("$0.04"));
        assertFalse(tip.contains("estimated"));
    }

    @Test
    void estimateIsMarked() {
        ContextStatsLabel label = new ContextStatsLabel();
        label.showStats(2_500, true, 400_000, stats(0, 0, 0, false, 0));
        assertEquals("ctx ~2.5k/400k", label.getText());
        assertTrue(label.getToolTipText().contains("estimated"));
    }

    @Test
    void denominatorHiddenForUnknownModel() {
        ContextStatsLabel label = new ContextStatsLabel();
        label.showStats(800, false, 0, stats(800, 100, 0, false, 1));
        assertEquals("ctx 800", label.getText());
    }

    @Test
    void costHiddenWhenPricingUnknown() {
        ContextStatsLabel label = new ContextStatsLabel();
        label.showStats(1_000, false, 128_000, stats(1_000, 100, 0, false, 1));
        assertEquals("ctx 1k/128k", label.getText());
    }

    @Test
    void tokenAndCostFormatting() {
        assertEquals("999", ContextStatsLabel.formatTokens(999));
        assertEquals("2.5k", ContextStatsLabel.formatTokens(2_500));
        assertEquals("12k", ContextStatsLabel.formatTokens(12_000));
        assertEquals("1.5M", ContextStatsLabel.formatTokens(1_500_000));
        assertEquals("1M", ContextStatsLabel.formatTokens(1_000_000));

        assertEquals("0.04", ContextStatsLabel.formatUsd(0.0412));
        assertEquals("1.25", ContextStatsLabel.formatUsd(1.25));
        assertEquals("0.0042", ContextStatsLabel.formatUsd(0.00421));
        assertEquals("0.005", ContextStatsLabel.formatUsd(0.005));
        assertEquals("0.00", ContextStatsLabel.formatUsd(0));
        // sub-0.1-mil values round to "0.0000" - must not strip down to "0."
        assertEquals("0.00", ContextStatsLabel.formatUsd(0.00001));
    }
}
