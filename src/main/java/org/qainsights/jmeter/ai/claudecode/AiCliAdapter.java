package org.qainsights.jmeter.ai.claudecode;

/**
 * Interface for AI CLI adapters.
 * Allows extending the terminal integration to support multiple AI CLIs.
 */
public interface AiCliAdapter {
    /**
     * @return the display name of the CLI (e.g., "Claude Code")
     */
    String getName();

    /**
     * @return the binary path if detected, otherwise null
     */
    String getBinaryPath();

    /**
     * Detects if the CLI is available on the system PATH or default locations.
     * @return true if detected, false otherwise
     */
    boolean detect();
}
