package org.qainsights.jmeter.ai.claudecode;

import org.slf4j.Logger;

import java.util.Optional;

/**
 * Evaluates an {@link AiCliAdapter} for availability and emits log lines that
 * explain <em>why</em> a CLI was or was not picked up.
 * <p>
 * This exists so users can distinguish between the three failure modes that
 * otherwise look identical ("No AI CLIs were detected on your PATH"):
 * <ul>
 *   <li>disabled by config ({@code jmeter.ai.terminal.&lt;cli&gt;.enabled=false})</li>
 *   <li>enabled but the binary is not on PATH</li>
 *   <li>enabled and detected (the success case)</li>
 * </ul>
 * Extracted from {@code ClaudeCodePanel.detectAvailableClis()} so the branching
 * logic can be unit tested without a live Swing/JMeter environment.
 */
final class CliDetector {

    private CliDetector() {
    }

    /**
     * Evaluates a single adapter.
     * <p>
     * Logs at INFO level for every outcome:
     * <ul>
     *   <li>{@code "<name> detection skipped: disabled by config"} when not enabled</li>
     *   <li>{@code "<name> enabled but not found on PATH"} when enabled but absent</li>
     *   <li>{@code "<name> detected at <path>"} on success</li>
     * </ul>
     *
     * @param adapter the adapter to evaluate
     * @param log     the logger to emit diagnostic lines to
     * @return the adapter wrapped in an Optional when it is enabled and detected,
     *         otherwise an empty Optional
     */
    static Optional<AiCliAdapter> evaluate(AiCliAdapter adapter, Logger log) {
        if (!adapter.isEnabled()) {
            log.info("{} detection skipped: disabled by config", adapter.getName());
            return Optional.empty();
        }
        if (!adapter.detect()) {
            log.info("{} enabled but not found on PATH", adapter.getName());
            return Optional.empty();
        }
        log.info("{} detected at {}", adapter.getName(), adapter.getBinaryPath());
        return Optional.of(adapter);
    }
}
