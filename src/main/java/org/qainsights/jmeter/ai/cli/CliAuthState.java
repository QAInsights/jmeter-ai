package org.qainsights.jmeter.ai.cli;

/**
 * Provider-neutral view of a CLI's authentication state, so the UI can render
 * status and enable/disable actions without knowing which CLI it is looking at.
 * Implemented by the provider-specific enums ({@code CodexAuthStatus},
 * {@code ClaudeCodeAuthStatus}).
 */
public interface CliAuthState {

    /** Short label for the UI, e.g. {@code "✓ Signed in"}. */
    String label();

    /** True when the CLI executable was found. */
    boolean isInstalled();

    /** True when prompts can be sent, i.e. the CLI is installed and authenticated. */
    boolean canRunPrompts();
}
