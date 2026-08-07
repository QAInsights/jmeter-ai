package org.qainsights.jmeter.ai.service.usage;

import java.util.List;

/**
 * Rough prompt-size estimate for the context-stats label when no server-side
 * usage number exists yet (before the first response, or for providers whose
 * stream omits usage). The heuristic is the industry-standard ~4 chars per
 * token over the history exactly as it will be sent - i.e. callers pass
 * history with attachment markers already resolved to their inlined content -
 * plus a fixed allowance for the system prompt and per-message framing.
 * <p>
 * The label marks estimates with a {@code ~} so users can tell them apart
 * from server-reported numbers.
 */
public final class ContextEstimator {

    /** Allowance for the system prompt, per-message roles/framing, and tool scaffolding. */
    static final int OVERHEAD_TOKENS = 2000;

    /** Average characters per token for mixed English/code text. */
    static final int CHARS_PER_TOKEN = 4;

    private ContextEstimator() {
    }

    /** Estimated token count of the next request built on this (resolved) history. */
    public static long estimateTokens(List<String> resolvedHistory) {
        if (resolvedHistory == null || resolvedHistory.isEmpty()) {
            return 0;
        }
        long chars = 0;
        for (String turn : resolvedHistory) {
            if (turn != null) {
                chars += turn.length();
            }
        }
        return chars / CHARS_PER_TOKEN + OVERHEAD_TOKENS;
    }
}
