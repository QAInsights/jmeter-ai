package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.errors.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.usage.UsageStats;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link OpenAiService#generateStreamResponse} against a local SSE
 * fixture: request assembly, token/usage/completion callbacks and error mapping.
 */
class OpenAiServiceHttpStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a JMeter test assistant.";
    private static final String COMPLETIONS = "/v1/chat/completions";

    private StreamingFixtureServer fixture;
    private MockedStatic<AiConfig> aiConfig;
    private OpenAiService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StreamingFixtureServer();
        String baseUrl = fixture.origin() + "/v1";
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            switch (key) {
                case "openai.api.key": return "test-openai-key";
                case "openai.base.url": return baseUrl;
                case "openai.max.retries": return "0";
                case "openai.default.model": return "gpt-4o";
                case "openai.temperature": return "0.3";
                case "openai.max.tokens": return "512";
                case "openai.max.history.size": return "4";
                case "openai.system.prompt": return SYSTEM_PROMPT;
                default: return invocation.getArgument(1);
            }
        });
        service = new OpenAiService();
    }

    @AfterEach
    void tearDown() {
        aiConfig.close();
        fixture.close();
    }

    private static String chunk(String content) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static final String USAGE_CHUNK =
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o\","
                    + "\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5,\"total_tokens\":16}}";

    @Test
    void streamsTokensInOrderRecordsUsageAndCompletes() throws Exception {
        fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(
                chunk("Thread "), chunk("Group"), chunk(" ready"), USAGE_CHUNK));
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("Create a test plan"), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(List.of("Thread ", "Group", " ready"), callbacks.tokens);
        assertEquals(1, callbacks.completions.get());
        assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
        verify(usage).record("openai:gpt-4o", 11, 5);

        JsonNode body = fixture.onlyRequest().json();
        assertEquals("gpt-4o", body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        assertTrue(body.get("stream_options").get("include_usage").asBoolean());
        assertEquals(512, body.get("max_completion_tokens").asLong());
        assertEquals(0.3, body.get("temperature").asDouble(), 1e-6);
        assertEquals("Bearer test-openai-key", fixture.onlyRequest().header("Authorization"));
    }

    @Test
    void requestAlternatesRolesFiltersErrorsAndTruncatesHistory() throws Exception {
        fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(chunk("ok")));
        StreamCallbacks callbacks = new StreamCallbacks();
        // max history 4: the first two turns are dropped, then the Error: entry is filtered.
        List<String> history = List.of("old question", "old answer",
                "Add a sampler", "Added HTTP sampler", "Error: request timed out", "Now add a timer");

        service.generateStreamResponse(history, "gpt-4o",
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode messages = fixture.onlyRequest().json().get("messages");
        List<String> roles = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        messages.forEach(m -> {
            roles.add(m.get("role").asText());
            contents.add(m.get("content").asText());
        });
        assertEquals(List.of("system", "user", "system", "user"), roles);
        assertEquals(List.of(SYSTEM_PROMPT, "Add a sampler", "Assistant: Added HTTP sampler", "Now add a timer"),
                contents);
    }

    @Test
    void emptyHistorySendsDefaultGreeting() throws Exception {
        fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(chunk("hi")));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of(), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode messages = fixture.onlyRequest().json().get("messages");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("Hello, how can you help me with JMeter?", messages.get(1).get("content").asText());
    }

    @Test
    void fixedTemperatureModelsOmitTemperature() throws Exception {
        fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(chunk("hi")));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), "gpt-5",
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode body = fixture.onlyRequest().json();
        assertEquals("gpt-5", body.get("model").asText());
        assertFalse(body.has("temperature"));
    }

    @Test
    void unauthorizedFailsWithoutCompletingOrRecordingUsage() throws Exception {
        fixture.respondJson(COMPLETIONS, 401, "{\"error\":{\"message\":\"Incorrect API key provided\","
                + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_api_key\"}}");
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertTrue(callbacks.tokens.isEmpty());
        Exception error = callbacks.errors.get(0);
        // The SDK message ("401: Incorrect API key provided") carries no error-code keyword,
        // so the mapper falls through to the generic message; the SDK exception is kept as cause.
        assertEquals("An error occurred while communicating with the OpenAI API. Please try again later.",
                error.getMessage());
        assertInstanceOf(UnauthorizedException.class, error.getCause());
        verify(usage, never()).record(anyString(), anyLong(), anyLong());
    }

    @Test
    void errorCodeInServerMessageMapsToFriendlyError() throws Exception {
        fixture.respondJson(COMPLETIONS, 404, "{\"error\":{\"message\":\"model_not_found: gpt-x does not exist\","
                + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"model_not_found\"}}");
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), "gpt-x",
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertEquals("The selected model was not found. Please select a different model.",
                callbacks.errors.get(0).getMessage());
    }

    @Test
    void rateLimitMapsToQuotaMessageWithServerDetail() throws Exception {
        fixture.respondJson(COMPLETIONS, 429, "{\"error\":{\"message\":\"Tokens per minute limit reached\","
                + "\"type\":\"requests\",\"param\":null,\"code\":\"rate_limit_exceeded\"}}");
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        String message = callbacks.errors.get(0).getMessage();
        assertTrue(message.startsWith("Rate limit or token quota exceeded (HTTP 429)"), message);
        assertTrue(message.contains("Tokens per minute limit reached"), message);
        assertEquals(1, fixture.requests().size(), "max.retries=0 must not retry the 429");
    }

    @Test
    void unknownServerErrorMapsToGenericMessage() throws Exception {
        fixture.respondJson(COMPLETIONS, 500, "{\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}");
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertEquals("An error occurred while communicating with the OpenAI API. Please try again later.",
                callbacks.errors.get(0).getMessage());
    }
}
