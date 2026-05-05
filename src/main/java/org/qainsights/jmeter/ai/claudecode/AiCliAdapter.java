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
     *
     * @return true if detected, false otherwise
     */
    boolean detect();

    /**
     * @return true if the CLI is enabled, false otherwise
     */
    boolean isEnabled();

    /**
     * @return the default prompt for the CLI
     */
    String defaultPrompt();

    /**
     * Builds the full command array to launch this CLI.
     *
     * @param workingDirectory the test plan directory (may be null)
     * @return list of command tokens starting with the binary path
     */
    java.util.List<String> buildCommand(String workingDirectory);
}
