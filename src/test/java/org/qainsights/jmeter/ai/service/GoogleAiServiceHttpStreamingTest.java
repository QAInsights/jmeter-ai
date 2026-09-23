package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.service.usage.UsageStats;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link GoogleAiService#generateStreamResponse} with a real Google
 * GenAI client pointed at a local {@code streamGenerateContent?alt=sse} fixture.
 */
class GoogleAiServiceHttpStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a Gemini test assistant.";
    private static final String MODEL = "gemini-2.5-flash";
    private static final String STREAM = "/v1beta/models/" + MODEL + ":streamGenerateContent";

    private StreamingFixtureServer fixture;
    private MockedStatic<AiConfig> aiConfig;
    private GoogleAiService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StreamingFixtureServer();
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            switch (key) {
                case "google.default.model": return MODEL;
                case "google.temperature": return "0.2";
                case "google.max.history.size": return "10";
                case "google.max.tokens": return "777";
                case "google.system.prompt": return SYSTEM_PROMPT;
                default: return invocation.getArgument(1);
            }
        });
        Client client = Client.builder()
                .apiKey("test-google-key")
                .httpOptions(HttpOptions.builder()
                        .baseUrl(fixture.origin() + "/")
                        .apiVersion("v1beta")
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
        service = new GoogleAiService(client);
    }

    @AfterEach
    void tearDown() {
        aiConfig.close();
        fixture.close();
    }

    private static String chunk(String partsJson, String usageJson) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":" + partsJson + "},\"index\":0}]"
                + (usageJson == null ? "" : ",\"usageMetadata\":" + usageJson)
                + ",\"modelVersion\":\"" + MODEL + "\"}";
    }

    private static List<String> roles(JsonNode contents) {
        List<String> roles = new ArrayList<>();
        contents.forEach(c -> roles.add(c.get("role").asText()));
        return roles;
    }

    private static List<String> texts(JsonNode contents) {
        List<String> texts = new ArrayList<>();
        contents.forEach(c -> texts.add(c.get("parts").get(0).get("text").asText()));
        return texts;
    }

    @Test
    void routesThoughtsAndTextRecordsLastUsageAndCompletes() throws Exception {
        fixture.respondSse(STREAM, StreamingFixtureServer.dataSse(
                chunk("[{\"text\":\"Think about pacing.\",\"thought\":true}]", null),
                chunk("[{\"text\":\"Add a \"}]",
                        "{\"promptTokenCount\":33,\"candidatesTokenCount\":2,\"totalTokenCount\":35}"),
                chunk("[{\"text\":\"Gaussian Timer.\"}]",
                        "{\"promptTokenCount\":33,\"candidatesTokenCount\":7,\"totalTokenCount\":40}")));
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        service.setReasoningSettings(new ReasoningSettings(true, "low"));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
        assertEquals(List.of("Think about pacing."), callbacks.reasoning);
        assertEquals(List.of("Add a ", "Gaussian Timer."), callbacks.tokens);
        assertEquals(1, callbacks.completions.get());
        verify(usage).record("google:" + MODEL, 33, 7);

        StreamingFixtureServer.RecordedRequest request = fixture.onlyRequest();
        assertEquals(STREAM, request.path());
        assertEquals("alt=sse", request.query());
        assertEquals("test-google-key", request.header("x-goog-api-key"));
        JsonNode body = request.json();
        assertEquals(List.of("user", "model", "user"), roles(body.get("contents")));
        assertEquals(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"), texts(body.get("contents")));
        JsonNode config = body.get("generationConfig");
        assertEquals(0.2, config.get("temperature").asDouble(), 1e-6);
        assertEquals(777, config.get("maxOutputTokens").asInt());
        assertTrue(config.get("thinkingConfig").get("includeThoughts").asBoolean());
        assertEquals(SYSTEM_PROMPT, body.get("systemInstruction").get("parts").get(0).get("text").asText());
    }

    @Test
    void truncatesHistoryFiltersErrorsAndKeepsRolesAlternating() throws Exception {
        fixture.respondSse(STREAM, StreamingFixtureServer.dataSse(chunk("[{\"text\":\"ok\"}]", null)));
        List<String> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add("m" + i);
        }
        history.set(4, "Error: quota exceeded");
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(history, null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode contents = fixture.onlyRequest().json().get("contents");
        // last 10 = m3..m12, "Error:" (m5) removed, roles re-derived from the filtered index.
        assertEquals(List.of("m3", "m4", "m6", "m7", "m8", "m9", "m10", "m11", "m12"), texts(contents));
        assertEquals(List.of("user", "model", "user", "model", "user", "model", "user", "model", "user"),
                roles(contents));
    }

    @Test
    void emptyHistorySendsDefaultGreeting() throws Exception {
        fixture.respondSse(STREAM, StreamingFixtureServer.dataSse(chunk("[{\"text\":\"hi\"}]", null)));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of(), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode contents = fixture.onlyRequest().json().get("contents");
        assertEquals(List.of("user"), roles(contents));
        assertEquals(List.of("Hello, how can you help me with JMeter?"), texts(contents));
    }

    @Test
    void serverErrorReachesOnErrorWithoutCompleting() throws Exception {
        fixture.respondJson(STREAM, 500, "{\"error\":{\"code\":500,\"message\":\"Internal error\","
                + "\"status\":\"INTERNAL\"}}");
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertTrue(callbacks.tokens.isEmpty());
        assertTrue(callbacks.errors.get(0).getMessage().contains("Internal error"),
                () -> "unexpected error: " + callbacks.errors.get(0));
        verify(usage, never()).record(anyString(), anyLong(), anyLong());
    }
}
