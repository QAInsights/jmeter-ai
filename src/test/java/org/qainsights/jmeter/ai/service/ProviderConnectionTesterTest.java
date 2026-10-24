package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class ProviderConnectionTesterTest {
    private final Map<String, String> properties = new HashMap<>();
    private MockedStatic<AiConfig> aiConfig;

    @BeforeEach
    void setUp() {
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation ->
                properties.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @AfterEach
    void tearDown() {
        aiConfig.close();
    }

    @Test
    void providerListHintsAreConciseAndDescriptionsRemainDetailed() {
        for (ProviderSetupOption option : java.util.List.of(
                ProviderSetupOption.ANTHROPIC,
                ProviderSetupOption.OPENAI,
                ProviderSetupOption.DEEPSEEK,
                ProviderSetupOption.GROK,
                ProviderSetupOption.META_MUSE)) {
            assertEquals("Chat and Agent Mode", option.listHint());
        }
        assertEquals("Chat and Agent Mode, AI Studio key", ProviderSetupOption.GEMINI.listHint());
        assertEquals("Local models, no API key", ProviderSetupOption.OLLAMA.listHint());
        assertEquals("Connect Claude models for chat and Agent Mode.",
                ProviderSetupOption.ANTHROPIC.description());
        assertEquals("Connect Gemini models with an AI Studio key.",
                ProviderSetupOption.GEMINI.description());
    }

    @Test
    void sendsProviderSpecificUrlsAndAuthenticationHeaders() {
        properties.put("openai.base.url", "https://openai.example/v1/");
        properties.put("anthropic.base.url", "https://anthropic.example");
        properties.put("deepseek.base.url", "https://deepseek.example/v1");
        properties.put("grok.base.url", "https://grok.example/v1");
        properties.put("meta.base.url", "https://meta.example/v1");
        assertRequest(ProviderSetupOption.ANTHROPIC, "key-one",
                "https://anthropic.example/v1/models", "x-api-key", "key-one");
        assertRequest(ProviderSetupOption.OPENAI, "key-two",
                "https://openai.example/v1/models", "Authorization", "Bearer key-two");
        assertRequest(ProviderSetupOption.GEMINI, "key-three",
                "https://generativelanguage.googleapis.com/v1beta/models",
                "x-goog-api-key", "key-three");
        assertRequest(ProviderSetupOption.DEEPSEEK, "key-four",
                "https://deepseek.example/v1/models", "Authorization", "Bearer key-four");
        assertRequest(ProviderSetupOption.GROK, "key-five",
                "https://grok.example/v1/models", "Authorization", "Bearer key-five");
        assertRequest(ProviderSetupOption.META_MUSE, "key-six",
                "https://meta.example/v1/models", "Authorization", "Bearer key-six");
    }

    @Test
    void anthropicRequestIncludesVersionHeader() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        ProviderConnectionTester tester = tester(captured, 200, "{\"data\":[]}");

        tester.test(ProviderSetupOption.ANTHROPIC, "key-one");

        assertEquals("2023-06-01",
                captured.get().headers().firstValue("anthropic-version").orElseThrow());
    }

    @Test
    void parsesModelCountAndOllamaModelNames() {
        ProviderConnectionTester cloud = new ProviderConnectionTester(request ->
                new ProviderConnectionTester.Response(200, "{\"data\":[{},{}]}"));
        ProviderConnectionTester ollama = new ProviderConnectionTester(request ->
                new ProviderConnectionTester.Response(200,
                        "{\"models\":[{\"name\":\"llama3.1\"},{\"name\":\"qwen2.5\"}]}"));

        ProviderConnectionTester.Result cloudResult = cloud.test(ProviderSetupOption.OPENAI, "secret");
        ProviderConnectionTester.Result ollamaResult =
                ollama.test(ProviderSetupOption.OLLAMA, null, "localhost", 11434);

        assertEquals(ProviderConnectionTester.Outcome.OK, cloudResult.outcome());
        assertEquals(2, cloudResult.modelCount());
        assertTrue(cloudResult.ollamaModelNames().isEmpty());
        assertEquals(ProviderConnectionTester.Outcome.OK, ollamaResult.outcome());
        assertEquals(2, ollamaResult.modelCount());
        assertEquals(java.util.List.of("llama3.1", "qwen2.5"), ollamaResult.ollamaModelNames());
        assertNoKey(cloudResult, "secret");
        assertNoKey(ollamaResult, "secret");
    }

    @Test
    void usesConfiguredOllamaHostAndPortWithoutSendingAKey() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        ProviderConnectionTester tester = tester(captured, 200, "{\"models\":[]}");

        ProviderConnectionTester.Result result =
                tester.test(ProviderSetupOption.OLLAMA, "must-not-be-sent", "http://127.0.0.2", 12345);

        assertEquals("http://127.0.0.2:12345/api/tags", captured.get().uri().toString());
        assertTrue(captured.get().headers().firstValue("Authorization").isEmpty());
        assertNoKey(result, "must-not-be-sent");
    }

    @Test
    void normalizesOllamaHostsAndExtractsInlinePorts() {
        assertEquals(new ProviderConnectionTester.OllamaEndpoint("http://localhost", null),
                ProviderConnectionTester.normalizeOllamaHost("localhost"));
        assertEquals(new ProviderConnectionTester.OllamaEndpoint("http://localhost", null),
                ProviderConnectionTester.normalizeOllamaHost("http://localhost"));
        assertEquals(new ProviderConnectionTester.OllamaEndpoint("http://192.0.2.10", "11500"),
                ProviderConnectionTester.normalizeOllamaHost("192.0.2.10:11500"));
        assertEquals(new ProviderConnectionTester.OllamaEndpoint("http://localhost", null),
                ProviderConnectionTester.normalizeOllamaHost("http://localhost/"));
    }

    @Test
    void usesAnInlineOllamaPortForTheTestedUrl() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        ProviderConnectionTester tester = tester(captured, 200, "{\"models\":[]}");

        tester.test(ProviderSetupOption.OLLAMA, null, "192.0.2.10:11500", 11434);

        assertEquals("http://192.0.2.10:11500/api/tags", captured.get().uri().toString());
    }

    @Test
    void mapsAuthRejectedRateLimitAndHttpErrorsWithoutExposingKey() {
        String key = "private-test-key";
        assertOutcome(401, ProviderConnectionTester.Outcome.AUTH_REJECTED, key);
        assertOutcome(403, ProviderConnectionTester.Outcome.AUTH_REJECTED, key);
        assertOutcome(429, ProviderConnectionTester.Outcome.RATE_LIMITED, key);
        ProviderConnectionTester.Result error = tester(new AtomicReference<>(), 503, "{}")
                .test(ProviderSetupOption.OPENAI, key);
        assertEquals(ProviderConnectionTester.Outcome.HTTP_ERROR, error.outcome());
        assertEquals(503, error.httpCode());
        assertNoKey(error, key);
    }

    @Test
    void mapsIoExceptionsToRedactedNetworkErrors() {
        String key = "token-should-not-appear";
        ProviderConnectionTester tester = new ProviderConnectionTester(request -> {
            throw new IOException("Failed https://alice:secret@example.test/models?token=" + key);
        });

        ProviderConnectionTester.Result result = tester.test(ProviderSetupOption.OPENAI, key);

        assertEquals(ProviderConnectionTester.Outcome.NETWORK_ERROR, result.outcome());
        assertTrue(result.reason().contains("https://***@example.test/models?***"));
        assertNoKey(result, key);
    }

    private void assertRequest(
            ProviderSetupOption option, String key, String expectedUrl, String header, String expectedHeader) {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        ProviderConnectionTester tester = tester(captured, 200, "{\"data\":[]}");

        ProviderConnectionTester.Result result = tester.test(option, key);

        assertEquals(expectedUrl, captured.get().uri().toString());
        assertEquals(expectedHeader, captured.get().headers().firstValue(header).orElseThrow());
        assertEquals("GET", captured.get().method());
        assertNoKey(result, key);
    }

    private void assertOutcome(int code, ProviderConnectionTester.Outcome outcome, String key) {
        ProviderConnectionTester.Result result = tester(new AtomicReference<>(), code, "{}")
                .test(ProviderSetupOption.OPENAI, key);
        assertEquals(outcome, result.outcome());
        assertNoKey(result, key);
    }

    private ProviderConnectionTester tester(
            AtomicReference<HttpRequest> captured, int status, String body) {
        return new ProviderConnectionTester(request -> {
            captured.set(request);
            return new ProviderConnectionTester.Response(status, body);
        });
    }

    private static void assertNoKey(ProviderConnectionTester.Result result, String key) {
        assertFalse(result.toString().contains(key), result.toString());
        assertFalse(result.reason() != null && result.reason().contains(key), result.toString());
    }
}
