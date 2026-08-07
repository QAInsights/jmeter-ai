package org.qainsights.jmeter.ai.service.usage;

import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

/**
 * Session-scoped token/cost accumulator for the chat panel's context-stats
 * label. Every provider service receives the instance via
 * {@code AiService.setUsageStats} and calls {@link #record} once per
 * completed response; the panel reads {@link #snapshot()} to render.
 * <p>
 * Cost is priced per call against the vendored models.dev catalog at record
 * time, so switching models mid-conversation prices each turn correctly.
 * Models without catalog pricing contribute tokens but no cost, and
 * {@link Snapshot#costKnown()} tells the label to hide the cost part rather
 * than show a misleading $0.00.
 * <p>
 * All state is guarded by the instance lock - recording happens on stream
 * completion worker threads while the label reads on the EDT.
 */
public final class UsageStats {

    /** Point-in-time view for the label. */
    public record Snapshot(long totalInput, long totalOutput, double costUsd, boolean costKnown,
            long lastInputTokens, int calls) {
        /** Total tokens across the session. */
        public long totalTokens() {
            return totalInput + totalOutput;
        }
    }

    private long totalInput;
    private long totalOutput;
    private double costUsd;
    private boolean costKnown;
    private long lastInputTokens;
    private int calls;

    /**
     * Records one completed response. Negative counts are rejected, and a
     * (0, 0) pair is ignored entirely - it carries no information (some SDKs
     * report zeros when usage is absent) and would otherwise bump the call
     * count and muddy the label.
     */
    public synchronized void record(String model, long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0 || (inputTokens == 0 && outputTokens == 0)) {
            return;
        }
        totalInput += inputTokens;
        totalOutput += outputTokens;
        lastInputTokens = inputTokens;
        calls++;

        var caps = ModelCapabilityCatalog.getInstance().capabilities(model);
        if (caps.isPresent() && caps.get().hasCost()) {
            costUsd += (inputTokens * caps.get().getCostIn() + outputTokens * caps.get().getCostOut()) / 1_000_000.0;
            costKnown = true;
        }
    }

    /** Current totals; {@code calls == 0} means nothing has been recorded yet. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(totalInput, totalOutput, costUsd, costKnown, lastInputTokens, calls);
    }

    /** The most recent server-reported prompt size, or -1 when never recorded. */
    public synchronized long lastInputTokens() {
        return calls == 0 ? -1 : lastInputTokens;
    }

    /** Clears all totals (new conversation). */
    public synchronized void reset() {
        totalInput = 0;
        totalOutput = 0;
        costUsd = 0;
        costKnown = false;
        lastInputTokens = 0;
        calls = 0;
    }
}
