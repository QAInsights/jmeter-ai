package org.qainsights.jmeter.ai.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Parsing of {@code codex login status} output into {@link CodexAuthStatus}. */
class CodexAuthStatusTest {

    @Test
    void detectsChatGptLogin() {
        assertEquals(CodexAuthStatus.CHATGPT,
                CodexAuthStatus.parse("Logged in using ChatGPT", "", 0));
    }

    @Test
    void detectsApiKeyLogin() {
        assertEquals(CodexAuthStatus.API_KEY,
                CodexAuthStatus.parse("Logged in using an API key", "", 0));
    }

    @Test
    void detectsNotLoggedIn() {
        assertEquals(CodexAuthStatus.NOT_LOGGED_IN,
                CodexAuthStatus.parse("Not logged in", "", 1));
    }

    @Test
    void unexpectedOutputWithZeroExitIsUnknown() {
        assertEquals(CodexAuthStatus.UNKNOWN,
                CodexAuthStatus.parse("Account: acme (workspace)", "", 0));
    }

    @Test
    void unexpectedOutputWithNonZeroExitCountsAsSignedOut() {
        assertEquals(CodexAuthStatus.NOT_LOGGED_IN,
                CodexAuthStatus.parse("", "something went wrong", 3));
    }

    @Test
    void loggedInWithoutAMethodIsNotClaimedAsChatGpt() {
        assertEquals(CodexAuthStatus.UNKNOWN,
                CodexAuthStatus.parse("Logged in", "", 0));
    }

    @Test
    void oldCliWithoutTheStatusSubcommandIsUnknown() {
        assertEquals(CodexAuthStatus.UNKNOWN,
                CodexAuthStatus.parse("", "error: unrecognized subcommand 'status'", 2));
    }

    @Test
    void onlyAuthenticatedStatesCanRunPrompts() {
        assertTrue(CodexAuthStatus.CHATGPT.canRunPrompts());
        assertTrue(CodexAuthStatus.API_KEY.canRunPrompts());
        assertFalse(CodexAuthStatus.NOT_LOGGED_IN.canRunPrompts());
        assertFalse(CodexAuthStatus.UNKNOWN.canRunPrompts());
        assertFalse(CodexAuthStatus.CODEX_NOT_INSTALLED.isInstalled());
    }
}
