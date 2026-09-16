package org.qainsights.jmeter.ai.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;
import com.google.genai.errors.ClientException;
import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;

class RateLimitErrorsTest {

    private static RateLimitException openAi429(String message) {
        return RateLimitException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder().message(message).code("quota").param("").type("rate_limit").build())
                .build();
    }

    @Test
    void detectsOpenAiRateLimitDirectlyAndAsCause() {
        RateLimitException e = openAi429("Allocated token quota for the client is exceeded.");
        assertTrue(RateLimitErrors.isRateLimited(e));
        assertTrue(RateLimitErrors.isRateLimited(new IllegalStateException("wrapped", e)));
    }

    @Test
    void detectsAnthropicAndGeminiRateLimits() {
        com.anthropic.errors.RateLimitException anthropic = com.anthropic.errors.RateLimitException.builder()
                .headers(com.anthropic.core.http.Headers.builder().build())
                .body(JsonValue.from("quota exceeded"))
                .build();
        assertTrue(RateLimitErrors.isRateLimited(anthropic));
        assertTrue(RateLimitErrors.isRateLimited(new RuntimeException(anthropic)));
        assertTrue(RateLimitErrors.describe(anthropic).startsWith("Rate limit or token quota exceeded (HTTP 429)"));

        assertTrue(RateLimitErrors.isRateLimited(new ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded")));
        assertFalse(RateLimitErrors.isRateLimited(new ClientException(400, "INVALID_ARGUMENT", "bad request")));
    }

    @Test
    void survivesCauseCycles() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(RateLimitErrors.isRateLimited(a));
        assertEquals("a", RateLimitErrors.serverMessage(a));
    }

    @Test
    void describeDoesNotDoublePunctuationAndTailorsAdviceToMode() {
        String agent = RateLimitErrors.describe(openAi429("quota exceeded."));
        assertTrue(agent.contains("quota exceeded.\n\n"), agent);
        assertFalse(agent.contains("exceeded.."), agent);

        String chat = RateLimitErrors.describe(openAi429("quota exceeded"), false);
        assertTrue(chat.contains("quota exceeded.\n\n"), chat);
        assertFalse(chat.contains("tool definitions"), chat);
        assertFalse(chat.contains("jmeter.ai.agent.max.tokens"), chat);
        assertTrue(chat.contains("openai.max.retries=0"), chat);
    }

    @Test
    void ignoresOtherFailures() {
        assertFalse(RateLimitErrors.isRateLimited(new IllegalStateException("429 in text only")));
        assertFalse(RateLimitErrors.isRateLimited(null));
    }

    @Test
    void describeKeepsServerMessageWithoutStatusPrefix() {
        RateLimitException e = openAi429("Allocated token quota for the client is exceeded.");
        String text = RateLimitErrors.describe(new RuntimeException(e));
        assertTrue(text.startsWith("Rate limit or token quota exceeded (HTTP 429): Allocated token quota"), text);
        assertFalse(text.contains("429: Allocated"), text);
        assertTrue(text.contains("openai.max.retries=0"), text);
        assertTrue(text.contains("jmeter.ai.agent.max.tokens"), text);
    }

    @Test
    void serverMessageStripsPrefixFromPlainThrowables() {
        assertEquals("quota gone", RateLimitErrors.serverMessage(new RuntimeException("429: quota gone")));
        assertEquals("", RateLimitErrors.serverMessage(new RuntimeException((String) null)));
    }
}
