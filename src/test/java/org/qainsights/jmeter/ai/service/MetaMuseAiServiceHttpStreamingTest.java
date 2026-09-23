package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.okhttp.OpenAIOkHttpClient;
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
 * Drives {@link MetaMuseAiService#generateStreamResponse} (OpenAI Responses
 * API) against a local SSE fixture serving {@code /v1/responses} events.
 */
class MetaMuseAiServiceHttpStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a Muse test assistant.";
    private static final String RESPONSES = "/v1/responses";
    private static final String MODEL = "muse-spark-1.1";

    private StreamingFixtureServer fixture;
    private MockedStatic<AiConfig> aiConfig;
    private MetaMuseAiService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StreamingFixtureServer();
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        String baseUrl = fixture.origin() + "/v1";
        service = new MetaMuseAiService(
                OpenAIOkHttpClient.builder().apiKey("test-meta-key").baseUrl(baseUrl).maxRetries(0).build(),
                baseUrl, MODEL, 0.4f, 10, 2048L, SYSTEM_PROMPT);
    }

    @AfterEach
    void tearDown() {
        aiConfig.close();
        fixture.close();
    }

    private static String completedResponse() {
        return "{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1,\"status\":\"completed\","
                + "\"model\":\"" + MODEL + "\",\"output\":[],\"parallel_tool_calls\":false,"
                + "\"tool_choice\":\"auto\",\"tools\":[],\"temperature\":0.4,\"top_p\":1.0,"
                + "\"error\":null,\"incomplete_details\":null,\"instructions\":null,\"metadata\":{},"
                + "\"usage\":{\"input_tokens\":40,\"input_tokens_details\":{\"cached_tokens\":0},"
                + "\"output_tokens\":12,\"output_tokens_details\":{\"reasoning_tokens\":4},"
                + "\"total_tokens\":52}}";
    }

    private static String streamBody() {
        return StreamingFixtureServer.namedSse(
                "response.created", "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                        + completedResponse().replace("\"completed\"", "\"in_progress\"") + "}",
                "response.reasoning_summary_text.delta", "{\"type\":\"response.reasoning_summary_text.delta\","
                        + "\"item_id\":\"rs_1\",\"output_index\":0,\"summary_index\":0,"
                        + "\"delta\":\"Weighing timers.\",\"sequence_number\":1}",
                "response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                        + "\"output_index\":1,\"content_index\":0,\"delta\":\"Add a \",\"logprobs\":[],"
                        + "\"sequence_number\":2}",
                "response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                        + "\"output_index\":1,\"content_index\":0,\"delta\":\"Constant Timer.\",\"logprobs\":[],"
                        + "\"sequence_number\":3}",
                "response.completed", "{\"type\":\"response.completed\",\"sequence_number\":4,\"response\":"
                        + completedResponse() + "}");
    }

    @Test
    void routesSummaryAndTextRecordsCompletedUsageAndCompletes() throws Exception {
        fixture.respondSse(RESPONSES, streamBody());
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        service.setReasoningSettings(new ReasoningSettings(true, "high"));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(
                List.of("Add a sampler", "Added HTTP sampler", "Error: stream reset", "Now add a timer"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
        assertEquals(List.of("Weighing timers."), callbacks.reasoning);
        assertEquals(List.of("Add a ", "Constant Timer."), callbacks.tokens);
        assertEquals(1, callbacks.completions.get());
        verify(usage).record("meta:" + MODEL, 40, 12);

        StreamingFixtureServer.RecordedRequest request = fixture.onlyRequest();
        assertEquals(RESPONSES, request.path());
        assertEquals("Bearer test-meta-key", request.header("Authorization"));
        JsonNode body = request.json();
        assertEquals(MODEL, body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        assertEquals(SYSTEM_PROMPT, body.get("instructions").asText());
        assertEquals(2048, body.get("max_output_tokens").asLong());
        assertEquals("high", body.get("reasoning").get("effort").asText());
        assertEquals("auto", body.get("reasoning").get("summary").asText());

        List<String> roles = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        body.get("input").forEach(item -> {
            roles.add(item.get("role").asText());
            contents.add(item.get("content").asText());
        });
        assertEquals(List.of("user", "assistant", "user"), roles);
        assertEquals(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"), contents);
    }

    @Test
    void emptyHistorySendsDefaultGreeting() throws Exception {
        fixture.respondSse(RESPONSES, streamBody());
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of(), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        JsonNode input = fixture.onlyRequest().json().get("input");
        assertEquals(1, input.size());
        assertEquals("user", input.get(0).get("role").asText());
        assertEquals("Hello, how can you help me with JMeter?", input.get(0).get("content").asText());
    }

    @Test
    void serverErrorReachesOnErrorWithoutCompleting() throws Exception {
        fixture.respondJson(RESPONSES, 500, "{\"error\":{\"message\":\"Muse unavailable\",\"type\":\"server_error\"}}");
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertTrue(callbacks.tokens.isEmpty());
        assertTrue(callbacks.reasoning.isEmpty());
        verify(usage, never()).record(anyString(), anyLong(), anyLong());
    }
}
