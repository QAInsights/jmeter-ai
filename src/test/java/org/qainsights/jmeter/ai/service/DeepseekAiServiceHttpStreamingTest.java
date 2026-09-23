package org.qainsights.jmeter.ai.service;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
 * Drives both {@link DeepseekAiService} streaming formats (OpenAI Chat
 * Completions and Anthropic Messages) against a local SSE fixture.
 */
class DeepseekAiServiceHttpStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a DeepSeek test assistant.";
    private static final List<String> HISTORY =
            List.of("Add a sampler", "Added HTTP sampler", "Error: rate limited", "Now add a timer");

    private StreamingFixtureServer fixture;

    @BeforeEach
    void startFixture() throws Exception {
        fixture = new StreamingFixtureServer();
    }

    @AfterEach
    void stopFixture() {
        fixture.close();
    }

    private static List<String> field(JsonNode messages, String name) {
        List<String> values = new ArrayList<>();
        messages.forEach(m -> values.add(m.get(name).asText()));
        return values;
    }

    @Nested
    class OpenAiFormat {

        private static final String COMPLETIONS = "/chat/completions";
        private DeepseekAiService service;

        @BeforeEach
        void setUp() {
            String baseUrl = fixture.origin();
            service = new DeepseekAiService(
                    OpenAIOkHttpClient.builder().apiKey("test-deepseek-key").baseUrl(baseUrl).maxRetries(0).build(),
                    null, false, baseUrl, "deepseek-reasoner", 0.5f, 10, 1024L, SYSTEM_PROMPT);
        }

        private String chunk(String deltaJson) {
            return "{\"id\":\"d-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-reasoner\","
                    + "\"choices\":[{\"index\":0,\"delta\":" + deltaJson + ",\"finish_reason\":null}]}";
        }

        @Test
        void streamsReasoningAndTokensRecordsUsageAndCompletes() throws Exception {
            fixture.respondSse(COMPLETIONS, StreamingFixtureServer.openAiSse(
                    chunk("{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"Timers pace users.\"}"),
                    chunk("{\"content\":\"Add a \"}"),
                    chunk("{\"content\":\"Uniform Random Timer.\"}"),
                    "{\"id\":\"d-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-reasoner\","
                            + "\"choices\":[],\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":8,"
                            + "\"total_tokens\":29}}"));
            UsageStats usage = mock(UsageStats.class);
            service.setUsageStats(usage);
            StreamCallbacks callbacks = new StreamCallbacks();

            service.generateStreamResponse(HISTORY, null,
                    callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
            callbacks.awaitTerminal();

            assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
            assertEquals(List.of("Timers pace users."), callbacks.reasoning);
            assertEquals(List.of("Add a ", "Uniform Random Timer."), callbacks.tokens);
            assertEquals(1, callbacks.completions.get());
            verify(usage).record("deepseek:deepseek-reasoner", 21, 8);

            StreamingFixtureServer.RecordedRequest request = fixture.onlyRequest();
            assertEquals(COMPLETIONS, request.path());
            assertEquals("Bearer test-deepseek-key", request.header("Authorization"));
            JsonNode body = request.json();
            assertEquals("deepseek-reasoner", body.get("model").asText());
            assertEquals(List.of("system", "user", "assistant", "user"), field(body.get("messages"), "role"));
            assertEquals(List.of(SYSTEM_PROMPT, "Add a sampler", "Added HTTP sampler", "Now add a timer"),
                    field(body.get("messages"), "content"));
        }

        @Test
        void serverErrorReachesOnError() throws Exception {
            fixture.respondJson(COMPLETIONS, 503, "{\"error\":{\"message\":\"Server busy\",\"type\":\"server_error\"}}");
            UsageStats usage = mock(UsageStats.class);
            service.setUsageStats(usage);
            StreamCallbacks callbacks = new StreamCallbacks();

            service.generateStreamResponse(HISTORY, null,
                    callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
            callbacks.awaitTerminal();

            assertEquals(1, callbacks.errors.size());
            assertEquals(0, callbacks.completions.get());
            assertTrue(callbacks.tokens.isEmpty());
            verify(usage, never()).record(anyString(), anyLong(), anyLong());
        }
    }

    @Nested
    class AnthropicFormat {

        private static final String MESSAGES = "/anthropic/v1/messages";
        private DeepseekAiService service;

        @BeforeEach
        void setUp() {
            String baseUrl = fixture.origin() + "/anthropic";
            service = new DeepseekAiService(null,
                    AnthropicOkHttpClient.builder().apiKey("test-deepseek-key").baseUrl(baseUrl).maxRetries(0).build(),
                    true, baseUrl, "deepseek-chat", 0.5f, 10, 1024L, SYSTEM_PROMPT);
        }

        @Test
        void streamsTextDeltasRecordsUsageAndCompletes() throws Exception {
            fixture.respondSse(MESSAGES, StreamingFixtureServer.namedSse(
                    "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_d\",\"type\":\"message\","
                            + "\"role\":\"assistant\",\"model\":\"deepseek-chat\",\"content\":[],\"stop_reason\":null,"
                            + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":17,\"output_tokens\":0}}}",
                    "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                            + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Use a \"}}",
                    "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"timer.\"}}",
                    "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                    "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                            + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":6}}",
                    "message_stop", "{\"type\":\"message_stop\"}"));
            UsageStats usage = mock(UsageStats.class);
            service.setUsageStats(usage);
            StreamCallbacks callbacks = new StreamCallbacks();

            service.generateStreamResponse(HISTORY, null,
                    callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
            callbacks.awaitTerminal();

            assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
            assertEquals(List.of("Use a ", "timer."), callbacks.tokens);
            assertTrue(callbacks.reasoning.isEmpty());
            assertEquals(1, callbacks.completions.get());
            verify(usage).record("deepseek:deepseek-chat", 17, 6);

            StreamingFixtureServer.RecordedRequest request = fixture.onlyRequest();
            assertEquals(MESSAGES, request.path());
            assertEquals("test-deepseek-key", request.header("x-api-key"));
            JsonNode body = request.json();
            assertEquals("deepseek-chat", body.get("model").asText());
            assertEquals(SYSTEM_PROMPT, body.get("system").asText());
            assertEquals(0.5, body.get("temperature").asDouble(), 1e-6);
            assertEquals(List.of("user", "assistant", "user"), field(body.get("messages"), "role"));
            assertEquals(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"),
                    field(body.get("messages"), "content"));
        }

        @Test
        void serverErrorReachesOnError() throws Exception {
            fixture.respondJson(MESSAGES, 529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                    + "\"message\":\"Overloaded\"}}");
            StreamCallbacks callbacks = new StreamCallbacks();

            service.generateStreamResponse(HISTORY, null,
                    callbacks.onToken, callbacks.onComplete, callbacks.onError);
            callbacks.awaitTerminal();

            assertEquals(1, callbacks.errors.size());
            assertEquals(0, callbacks.completions.get());
            assertTrue(callbacks.tokens.isEmpty());
        }
    }
}
