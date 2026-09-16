package org.qainsights.jmeter.ai.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
