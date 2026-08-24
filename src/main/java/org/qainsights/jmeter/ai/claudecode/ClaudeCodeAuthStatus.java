package org.qainsights.jmeter.ai.claudecode;

import java.util.Locale;

import org.qainsights.jmeter.ai.cli.CliAuthState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Authentication state of the local Claude Code CLI, as reported by
 * {@code claude auth status --json}
 * ({@code {"loggedIn":false,"authMethod":"none",...}}). Falls back to text
 * matching and then to the exit code so a changed payload degrades to
 * {@link #UNKNOWN} rather than claiming the user is signed in.
 */
public enum ClaudeCodeAuthStatus implements CliAuthState {

    /** Signed in with a Claude subscription account (no API key involved). */
    SUBSCRIPTION("\u2713 Signed in", true, true),
    /** Signed in with an Anthropic Console API key or a cloud provider credential. */
    API_KEY("Signed in using API key", true, true),
    NOT_LOGGED_IN("Not signed in", true, false),
    CLI_NOT_INSTALLED("Claude Code CLI not installed", false, false),
    UNKNOWN("Unable to determine status", true, false);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String label;
    private final boolean installed;
    private final boolean canRunPrompts;

    ClaudeCodeAuthStatus(String label, boolean installed, boolean canRunPrompts) {
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

    /** Maps one {@code claude auth status} run onto a status. */
    public static ClaudeCodeAuthStatus parse(String stdout, String stderr, int exitCode) {
        ClaudeCodeAuthStatus fromJson = fromJson(stdout);
        if (fromJson != null) {
            return fromJson;
        }
        String combined = ((stdout == null ? "" : stdout) + '\n' + (stderr == null ? "" : stderr))
                .toLowerCase(Locale.ROOT);
        if (combined.contains("unknown command") || combined.contains("unknown option")) {
            return UNKNOWN;
        }
        if (combined.contains("not logged in") || combined.contains("not signed in")
                || combined.contains("logged out")) {
            return NOT_LOGGED_IN;
        }
        if (combined.contains("logged in") || combined.contains("signed in")) {
            return isApiKeyMethod(combined) ? API_KEY : SUBSCRIPTION;
        }
        return exitCode == 0 ? UNKNOWN : NOT_LOGGED_IN;
    }

    /** Reads the {@code loggedIn}/{@code authMethod} pair, or null when the payload is not JSON. */
    private static ClaudeCodeAuthStatus fromJson(String stdout) {
        if (stdout == null) {
            return null;
        }
        int start = stdout.indexOf('{');
        int end = stdout.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(stdout.substring(start, end + 1));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
        JsonNode loggedIn = root.get("loggedIn");
        if (loggedIn == null || !loggedIn.isBoolean()) {
            return null;
        }
        if (!loggedIn.asBoolean()) {
            return NOT_LOGGED_IN;
        }
        JsonNode method = root.get("authMethod");
        String methodText = method == null ? "" : method.asText("").toLowerCase(Locale.ROOT);
        return isApiKeyMethod(methodText) ? API_KEY : SUBSCRIPTION;
    }

    private static boolean isApiKeyMethod(String text) {
        return text.contains("apikey") || text.contains("api key") || text.contains("api_key")
                || text.contains("console") || text.contains("bedrock") || text.contains("vertex")
                || text.contains("foundry");
    }
}
