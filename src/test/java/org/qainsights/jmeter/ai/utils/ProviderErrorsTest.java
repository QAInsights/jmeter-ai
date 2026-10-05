package org.qainsights.jmeter.ai.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.openai.core.http.Headers;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.ErrorObject;

class ProviderErrorsTest {

    private static ErrorObject error(String message) {
        return ErrorObject.builder().message(message).code("").param("").type("").build();
    }

    @Test
    void unauthorizedPointsAtTheApiKeyWithoutEchoingTheServerMessage() {
        UnauthorizedException e = UnauthorizedException.builder()
                .headers(Headers.builder().build())
                .error(error("Incorrect API key provided: sk-abc***xyz"))
                .build();
        String text = ProviderErrors.describe(new ExecutionException(e));
        assertTrue(text.startsWith("Error: Authentication failed (HTTP 401)"), text);
        assertTrue(text.contains("API key"), text);
        assertFalse(text.contains("sk-abc"), text);
        assertTrue(text.endsWith(ProviderErrors.LOG_HINT), text);
        assertTrue(ProviderErrors.isConfigurationError(e));
    }

    @Test
    void anthropicPermissionDeniedIsAccessDenied() {
        com.anthropic.errors.PermissionDeniedException e = com.anthropic.errors.PermissionDeniedException.builder()
                .headers(com.anthropic.core.http.Headers.builder().build())
                .body(JsonValue.from("not allowed"))
                .build();
        assertTrue(ProviderErrors.describe(e).startsWith("Error: Access denied (HTTP 403)"));
        assertTrue(ProviderErrors.isConfigurationError(e));
    }

    @Test
    void notFoundMentionsModelAndBaseUrlAndKeepsProviderDetail() {
        NotFoundException e = NotFoundException.builder()
                .headers(Headers.builder().build())
                .error(error("The model `muse-x` does not exist"))
                .build();
        String text = ProviderErrors.describe(e);
        assertTrue(text.startsWith("Error: Not found (HTTP 404)"), text);
        assertTrue(text.contains("base.url"), text);
        assertTrue(text.contains("Provider said: The model `muse-x` does not exist."), text);
    }

    @Test
    void geminiStatusCodesAreRecognised() {
        assertTrue(ProviderErrors.describe(new ClientException(400, "INVALID_ARGUMENT", "bad field"))
                .startsWith("Error: The provider rejected the request (HTTP 400). Provider said: bad field."));
        assertTrue(ProviderErrors.describe(new ServerException(503, "UNAVAILABLE", "overloaded"))
                .startsWith("Error: The provider had a server error (HTTP 503)."));
        assertFalse(ProviderErrors.isConfigurationError(new ServerException(503, "UNAVAILABLE", "x")));
    }

    @Test
    void rateLimitsKeepTheirDedicatedAdvice() {
        String text = ProviderErrors.describe(new ClientException(429, "RESOURCE_EXHAUSTED", "Quota exceeded"));
        assertTrue(text.startsWith("Error: Rate limit or token quota exceeded (HTTP 429)"), text);
    }

    @Test
    void networkFailuresAreTimeoutOrUnreachable() {
        assertTrue(ProviderErrors.describe(new RuntimeException(new SocketTimeoutException("Read timed out")))
                .startsWith("Error: The request to the provider timed out."));
        assertTrue(ProviderErrors.describe(new OpenAIIoException("Request failed", new UnknownHostException("api.x")))
                .startsWith("Error: Could not reach the provider."));
        assertTrue(ProviderErrors.describe(new ConnectException("Connection refused"))
                .contains("Provider said: Connection refused."));
    }

    @Test
    void unknownFailuresFallBackToActionWithDetail() {
        String text = ProviderErrors.describe(new ExecutionException(new IllegalStateException("boom")),
                "running the agent", true);
        assertEquals("Error: Sorry, I encountered an error while running the agent. Provider said: boom.\n\n"
                + ProviderErrors.LOG_HINT, text);
        assertEquals("Error: Sorry, I encountered an error while processing your request.\n\n"
                + ProviderErrors.LOG_HINT, ProviderErrors.describe(new RuntimeException()));
    }

    @Test
    void detailIsCappedAndStripsStatusPrefix() {
        assertEquals("bad key", ProviderErrors.detail(new RuntimeException("401: bad key")));
        String longDetail = ProviderErrors.detail(new RuntimeException("x".repeat(1000)));
        assertTrue(longDetail.length() <= 303, String.valueOf(longDetail.length()));
        assertTrue(longDetail.endsWith("..."));
    }

    @Test
    void survivesCauseCycles() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertEquals(-1, ProviderErrors.statusCode(a));
        assertTrue(ProviderErrors.describe(a).startsWith("Error: Sorry"));
    }
}
