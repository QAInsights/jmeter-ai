package org.qainsights.jmeter.ai.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Parsing of {@code claude auth status --json} into {@link ClaudeCodeAuthStatus}. */
class ClaudeCodeAuthStatusTest {

    @Test
    void detectsSubscriptionLogin() {
        String json = "{\"loggedIn\":true,\"authMethod\":\"claudeai\",\"apiProvider\":\"firstParty\"}";
        assertEquals(ClaudeCodeAuthStatus.SUBSCRIPTION, ClaudeCodeAuthStatus.parse(json, "", 0));
    }

    @Test
    void detectsConsoleApiKeyLogin() {
        String json = "{\"loggedIn\":true,\"authMethod\":\"apiKey\",\"apiProvider\":\"console\"}";
        assertEquals(ClaudeCodeAuthStatus.API_KEY, ClaudeCodeAuthStatus.parse(json, "", 0));
    }

    @Test
    void detectsBedrockCredentialAsApiKey() {
        String json = "{\"loggedIn\":true,\"authMethod\":\"bedrock\"}";
        assertEquals(ClaudeCodeAuthStatus.API_KEY, ClaudeCodeAuthStatus.parse(json, "", 0));
    }

    @Test
    void detectsNotLoggedIn() {
        String json = "{\"loggedIn\":false,\"authMethod\":\"none\",\"apiProvider\":\"firstParty\"}";
        assertEquals(ClaudeCodeAuthStatus.NOT_LOGGED_IN, ClaudeCodeAuthStatus.parse(json, "", 0));
    }

    @Test
    void fallsBackToTextWhenJsonIsUnsupported() {
        assertEquals(ClaudeCodeAuthStatus.NOT_LOGGED_IN,
                ClaudeCodeAuthStatus.parse("You are not logged in", "", 1));
    }

    @Test
    void malformedPayloadWithZeroExitIsUnknown() {
        assertEquals(ClaudeCodeAuthStatus.UNKNOWN,
                ClaudeCodeAuthStatus.parse("{\"loggedIn\":", "", 0));
    }

    @Test
    void oldCliWithoutTheAuthCommandIsUnknown() {
        assertEquals(ClaudeCodeAuthStatus.UNKNOWN,
                ClaudeCodeAuthStatus.parse("", "error: unknown command 'auth'", 1));
    }
}
