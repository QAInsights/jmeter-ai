package org.qainsights.jmeter.ai.cli;

/**
 * A locally installed coding-agent CLI (OpenAI Codex, Claude Code) used as an
 * AI backend through the subscription the user already signed into with that
 * CLI. The CLI owns the whole OAuth/browser flow and every network call:
 * Feather Wand never collects credentials and never reads the CLI's
 * credential files or tokens.
 */
public interface SubscriptionCliProvider {

    /** Provider name for the UI, e.g. {@code "ChatGPT / Codex"}. */
    String displayName();

    /** True unless the user disabled this provider via its enablement property. */
    boolean isEnabled();

    /** True when the CLI executable was found (PATH discovery or explicit override). */
    boolean isInstalled();

    /** Current authentication state, determined by asking the CLI itself. */
    CliAuthState getAuthStatus();

    /**
     * Starts the CLI's own interactive login and returns the state <em>verified</em>
     * afterwards - a zero exit code alone is never treated as signed in. Blocking:
     * call off the Swing EDT.
     */
    CliAuthState login();

    /** Signs out through the CLI and returns the state verified afterwards. */
    CliAuthState logout();

    /**
     * Runs one non-interactive prompt and returns the CLI's answer text.
     *
     * @throws CliProviderException with a user-facing message on any failure
     */
    String execute(String prompt);

    /** One-line hint on how to install the CLI, shown when it is missing. */
    String installHint();

    /** Label for the sign-in action, e.g. {@code "Sign in with ChatGPT"}. */
    String signInActionLabel();

    /** Selector-id prefix identifying this provider, e.g. {@code "codex:"}. */
    String modelPrefix();

    /** The model id the provider sends to the CLI, or empty for the CLI's own default. */
    String getModel();

    /** Selects the model id passed to the CLI; empty or null means the CLI default. */
    void setModel(String model);
}
