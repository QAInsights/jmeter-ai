package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.service.usage.UsageStats;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link GrokAiService#generateStreamResponse} with a real OpenAI SDK
 * client pointed at a local SSE fixture.
 */
class GrokAiServiceHttpStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a Grok test assistant.";
    private static final String COMPLETIONS = "/v1/chat/completions";

    private StreamingFixtureServer fixture;
    private GrokAiService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StreamingFixtureServer();
        String baseUrl = fixture.origin() + "/v1";
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey("test-grok-key")
                .baseUrl(baseUrl)
                .maxRetries(0)
                .build();
        service = new GrokAiService(client, baseUrl, "grok-4.5", 0.6f, 10, 2048L, SYSTEM_PROMPT);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private static String chunk(String deltaJson) {
        return "{\"id\":\"g-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"grok-4.5\","
                + "\"choices\":[{\"index\":0,\"delta\":" + deltaJson + ",\"finish_reason\":null}]}";
    }

    private static List<String> roles(JsonNode messages) {
        List<String> roles = new ArrayList<>();
        messages.forEach(m -> roles.add(m.get("role").asText()));
        return roles;
    }

    private static List<String> contents(JsonNode messages) {
        List<String> contents = new ArrayList<>();
        messages.forEach(m -> contents.add(m.get("content").asText()));
        return contents;
    }

    @Test
    void routesReasoningAndContentRecordsUsageAndCompletes() throws Exception {
        fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(
                chunk("{\"role\":\"assistant\",\"reasoning_content\":\"Consider timers.\"}"),
                chunk("{\"content\":\"Use a \"}"),
                chunk("{\"content\":\"Constant Timer.\"}"),
                "{\"id\":\"g-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"grok-4.5\","
                        + "\"choices\":[],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":9,"
                        + "\"total_tokens\":39}}"));
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        service.setReasoningSettings(new ReasoningSettings(true, "low"));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
        assertEquals(List.of("Consider timers."), callbacks.reasoning);
        assertEquals(List.of("Use a ", "Constant Timer."), callbacks.tokens);
        assertEquals(1, callbacks.completions.get());
        verify(usage).record("grok:grok-4.5", 30, 9);

        JsonNode body = fixture.onlyRequest().json();
        assertEquals("grok-4.5", body.get("model").asText());
        assertEquals("low", body.get("reasoning_effort").asText());
        assertTrue(body.get("stream_options").get("include_usage").asBoolean());
        assertEquals(List.of("system", "user", "assistant", "user"), roles(body.get("messages")));
        assertEquals(List.of(SYSTEM_PROMPT, "Add a sampler", "Added HTTP sampler", "Now add a timer"),
                contents(body.get("messages")));
        assertEquals("Bearer test-grok-key", fixture.onlyRequest().header("Authorization"));
    }

    @Test
    void truncatesToMaxHistoryAndFiltersErrorMessages() throws Exception {
        fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(chunk("{\"content\":\"ok\"}")));
        List<String> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add("m" + i);
        }
        history.set(10, "Error: upstream failed");
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(history, "grok-4.5",
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode messages = fixture.onlyRequest().json().get("messages");
        // last 10 = m3..m12, then "Error:" (m11) is dropped -> 9 history entries after the system prompt.
        assertEquals(List.of(SYSTEM_PROMPT, "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10", "m12"),
                contents(messages));
        assertEquals(List.of("system", "user", "assistant", "user", "assistant", "user", "assistant",
                "user", "assistant", "user"), roles(messages));
    }

    @Test
    void emptyHistorySendsDefaultGreeting() throws Exception {
        fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(chunk("{\"content\":\"hi\"}")));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("Error: previous failure"), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode messages = fixture.onlyRequest().json().get("messages");
        assertEquals(List.of("system", "user"), roles(messages));
        assertEquals("Hello, how can you help me with JMeter?", messages.get(1).get("content").asText());
    }

    @Test
    void serverErrorReachesOnErrorWithoutCompleting() throws Exception {
        fixture.respondJson(COMPLETIONS, 500, "{\"error\":{\"message\":\"xAI overloaded\",\"type\":\"server_error\"}}");
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertTrue(callbacks.tokens.isEmpty());
        assertTrue(callbacks.errors.get(0) instanceof com.openai.errors.InternalServerException,
                () -> "unexpected error type: " + callbacks.errors.get(0));
        verify(usage, never()).record(anyString(), anyLong(), anyLong());
    }
}
