package org.qainsights.jmeter.ai.gui;

import java.util.Locale;
import javax.swing.JLabel;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.usage.UsageStats;

/**
 * The compact context/cost readout in the input options row, e.g.
 * {@code "ctx 12.3k/400k · $0.04"}. The numerator is the server-reported
 * prompt size of the last response, or an estimate (marked with {@code ~})
 * before the first response or on providers that don't report usage. The
 * denominator is the model's context window from the vendored models.dev
 * catalog - hidden when the model is unknown. Cost is the session total at
 * models.dev list prices - hidden when the catalog has no pricing for any
 * model used. The tooltip carries the exact breakdown.
 */
class ContextStatsLabel extends JLabel {

    ContextStatsLabel() {
        setForeground(ThemeColors.secondaryText());
        setFont(getFont().deriveFont(getFont().getSize2D() - 2f));
        setText("");
    }

    /**
     * Renders the current stats. Call on the EDT.
     *
     * @param contextTokens prompt size of the last request (or estimate)
     * @param estimated     true when {@code contextTokens} is heuristic, not server-reported
     * @param contextWindow the selected model's context window (0 = unknown)
     * @param stats         session totals from {@link UsageStats}
     */
    void showStats(long contextTokens, boolean estimated, long contextWindow, UsageStats.Snapshot stats) {
        if (contextTokens <= 0 && stats.calls() == 0) {
            setText("");
            setToolTipText(null);
            return;
        }
        String marker = estimated ? "~" : "";
        StringBuilder text = new StringBuilder("ctx ").append(marker).append(formatTokens(contextTokens));
        if (contextWindow > 0) {
            text.append("/").append(ModelPickerRenderer.formatContext(contextWindow));
        }
        if (stats.costKnown()) {
            text.append(" · $").append(formatUsd(stats.costUsd()));
        }
        setText(text.toString());

        StringBuilder tip = new StringBuilder("<html>");
        tip.append("Context: ").append(formatExact(contextTokens));
        if (contextWindow > 0) {
            tip.append(" / ").append(formatExact(contextWindow))
                    .append(String.format(Locale.ROOT, " (%.1f%%)", contextTokens * 100.0 / contextWindow));
        }
        if (estimated) {
            tip.append(" (estimated)");
        }
        if (stats.calls() > 0) {
            tip.append("<br>Session: ").append(formatExact(stats.totalInput())).append(" in · ")
                    .append(formatExact(stats.totalOutput())).append(" out over ")
                    .append(stats.calls()).append(stats.calls() == 1 ? " response" : " responses");
        }
        if (stats.costKnown()) {
            tip.append("<br>Est. cost: $").append(formatUsd(stats.costUsd()))
                    .append(" (models.dev list price)");
        }
        setToolTipText(tip.append("</html>").toString());
    }

    /** Compact token count with one decimal: 12345 → "12.3k", 1500000 → "1.5M". */
    static String formatTokens(long tokens) {
        if (tokens >= 1_000_000) {
            return trimZero(tokens / 1_000_000.0) + "M";
        }
        if (tokens >= 1_000) {
            return trimZero(tokens / 1_000.0) + "k";
        }
        return Long.toString(tokens);
    }

    private static String trimZero(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", "");
    }

    /** USD with cents normally, mils for sub-cent amounts: 0.0412 → "0.04", 0.0042 → "0.0042". */
    static String formatUsd(double usd) {
        if (usd >= 0.01 || usd == 0) {
            return String.format(Locale.ROOT, "%.2f", usd);
        }
        String tiny = String.format(Locale.ROOT, "%.4f", usd).replaceAll("0+$", "");
        // values below 0.00005 round to "0.0000" and strip down to "0."
        return tiny.endsWith(".") ? "0.00" : tiny;
    }

    private static String formatExact(long tokens) {
        return String.format(Locale.ROOT, "%,d", tokens);
    }
}
