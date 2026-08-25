package org.qainsights.jmeter.ai.codex;

import java.util.Locale;

import org.qainsights.jmeter.ai.cli.CliAuthState;

/**
 * Authentication state of the local Codex CLI, as reported by
 * {@code codex login status}. Derived from the command's output first and its
 * exit code second, so a slightly reworded CLI message degrades to
 * {@link #UNKNOWN} or to the exit-code verdict instead of lying about being
 * signed in.
 */
public enum CodexAuthStatus implements CliAuthState {

    /** Signed in with a ChatGPT (subscription) account - no API key involved. */
    CHATGPT("\u2713 Signed in", true, true),
    /** Signed in with an OpenAI API key configured inside the Codex CLI. */
    API_KEY("Signed in using API key", true, true),
    NOT_LOGGED_IN("Not signed in", true, false),
    CODEX_NOT_INSTALLED("Codex CLI not installed", false, false),
    UNKNOWN("Unable to determine status", true, false);

    private final String label;
    private final boolean installed;
    private final boolean canRunPrompts;

    CodexAuthStatus(String label, boolean installed, boolean canRunPrompts) {
        this.label = label;
        this.installed = installed;
        this.canRunPrompts = canRunPrompts;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean isInstalled() {
        return installed;
    }

    @Override
    public boolean canRunPrompts() {
        return canRunPrompts;
    }

    /**
     * Maps one {@code codex login status} run onto a status.
     * <p>
     * Recognised today: {@code Not logged in}, {@code Logged in using ChatGPT},
     * {@code Logged in using an API key}. Anything else falls back to the exit
     * code - non-zero means not signed in, zero means "signed in, method
     * unknown" - and an unrecognised-subcommand message maps to
     * {@link #UNKNOWN} (Codex CLI too old).
     */
    public static CodexAuthStatus parse(String stdout, String stderr, int exitCode) {
        String combined = ((stdout == null ? "" : stdout) + '\n' + (stderr == null ? "" : stderr))
                .toLowerCase(Locale.ROOT);
        if (combined.contains("unrecognized subcommand") || combined.contains("unexpected argument")
                || combined.contains("unrecognised subcommand")) {
            return UNKNOWN;
        }
        if (combined.contains("not logged in") || combined.contains("not signed in")
                || combined.contains("no credentials")) {
            return NOT_LOGGED_IN;
        }
        if (combined.contains("logged in") || combined.contains("signed in")) {
            if (combined.contains("chatgpt")) {
                return CHATGPT;
            }
            if (combined.contains("api key")) {
                return API_KEY;
            }
            return UNKNOWN;
        }
        return exitCode == 0 ? UNKNOWN : NOT_LOGGED_IN;
    }
}
