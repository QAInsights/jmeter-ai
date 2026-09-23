package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Drives {@link ClaudeService#generateStreamResponse} against a local Anthropic
 * Messages SSE fixture: thinking/text routing, usage, system-prompt-once,
 * role alternation, thinking vs temperature, and error mapping.
 */
class ClaudeServiceHttpStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a JMeter test assistant.";
    private static final String MESSAGES = "/v1/messages";
    private static final String THINKING_MODEL = "claude-opus-4-8-20251101";

    private StreamingFixtureServer fixture;
    private MockedStatic<AiConfig> aiConfig;
    private ClaudeService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StreamingFixtureServer();
        String baseUrl = fixture.origin();
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            switch (key) {
                case "anthropic.api.key": return "test-anthropic-key";
                case "anthropic.base.url": return baseUrl;
                case "anthropic.max.retries": return "0";
                case "anthropic.log.level": return "";
                case "claude.default.model": return "claude-sonnet-4-6";
                case "claude.temperature": return "0.4";
                case "claude.max.tokens": return "1024";
                case "claude.max.history.size": return "10";
                case "claude.system.prompt": return SYSTEM_PROMPT;
                default: return invocation.getArgument(1);
            }
        });
        service = new ClaudeService();
    }

    @AfterEach
    void tearDown() {
        aiConfig.close();
        fixture.close();
    }

    private static String streamBody(String model) {
        return StreamingFixtureServer.namedSse(
                "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"model\":\"" + model + "\",\"content\":[],\"stop_reason\":null,"
                        + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}",
                "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}",
                "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Plan the sampler.\"}}",
                "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}",
                "content_block_start", "{\"type\":\"content_block_start\",\"index\":1,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Add an \"}}",
                "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"HTTP sampler.\"}}",
                "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}",
                "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                        + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":42}}",
                "message_stop", "{\"type\":\"message_stop\"}");
    }

    @Test
    void routesThinkingAndTextRecordsServerUsageAndCompletes() throws Exception {
        fixture.respondSse(MESSAGES, streamBody(THINKING_MODEL));
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        service.setReasoningSettings(new ReasoningSettings(true, "medium"));
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("Add a sampler"), THINKING_MODEL,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
        assertEquals(List.of("Plan the sampler."), callbacks.reasoning);
        assertEquals(List.of("Add an ", "HTTP sampler."), callbacks.tokens);
        assertEquals(1, callbacks.completions.get());
        verify(usage).record(THINKING_MODEL, 25, 42);

        JsonNode body = fixture.onlyRequest().json();
        assertEquals(THINKING_MODEL, body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        assertTrue(body.has("thinking"), "thinking config must be sent when enabled: " + body);
        assertFalse(body.has("temperature"), "extended thinking forbids temperature: " + body);
        assertTrue(body.get("max_tokens").asLong() > body.get("thinking").get("budget_tokens").asLong());
        assertEquals("test-anthropic-key", fixture.onlyRequest().header("x-api-key"));
    }

    @Test
    void sendsSystemPromptOnlyOnFirstRequestAndAlternatesRoles() throws Exception {
        fixture.respondSse(MESSAGES, streamBody("claude-sonnet-4-6"));

        StreamCallbacks first = new StreamCallbacks();
        service.generateStreamResponse(List.of("Add a sampler"), null,
                first.onToken, first.onComplete, first.onError);
        first.awaitTerminal();

        StreamCallbacks second = new StreamCallbacks();
        service.generateStreamResponse(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"), null,
                second.onToken, second.onComplete, second.onError);
        second.awaitTerminal();

        List<StreamingFixtureServer.RecordedRequest> requests = fixture.requests();
        assertEquals(2, requests.size());

        JsonNode firstBody = requests.get(0).json();
        assertEquals(SYSTEM_PROMPT, firstBody.get("system").asText());
        assertEquals(0.4, firstBody.get("temperature").asDouble(), 1e-6);
        assertFalse(firstBody.has("thinking"));

        JsonNode secondBody = requests.get(1).json();
        assertFalse(secondBody.has("system"), "system prompt must only be sent once: " + secondBody);
        List<String> roles = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        secondBody.get("messages").forEach(m -> {
            roles.add(m.get("role").asText());
            contents.add(m.get("content").asText());
        });
        assertEquals(List.of("user", "assistant", "user"), roles);
        assertEquals(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"), contents);
    }

    @Test
    void resetSystemPromptInitializationResendsSystemPrompt() throws Exception {
        fixture.respondSse(MESSAGES, streamBody("claude-sonnet-4-6"));

        StreamCallbacks first = new StreamCallbacks();
        service.generateStreamResponse(List.of("one"), null, first.onToken, first.onComplete, first.onError);
        first.awaitTerminal();
        service.resetSystemPromptInitialization();
        StreamCallbacks second = new StreamCallbacks();
        service.generateStreamResponse(List.of("two"), null, second.onToken, second.onComplete, second.onError);
        second.awaitTerminal();

        assertEquals(SYSTEM_PROMPT, fixture.requests().get(1).json().get("system").asText());
    }

    @Test
    void creditBalanceErrorMapsToFriendlyMessage() throws Exception {
        fixture.respondJson(MESSAGES, 400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}");
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertTrue(callbacks.tokens.isEmpty());
        assertEquals("Your credit balance is too low to access the Anthropic API. "
                + "Please go to Plans & Billing to upgrade or purchase credits.", callbacks.errors.get(0).getMessage());
        verify(usage, never()).record(anyString(), anyLong(), anyLong());
    }

    @Test
    void unknownServerErrorMapsToGenericMessage() throws Exception {
        fixture.respondJson(MESSAGES, 500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\","
                + "\"message\":\"Internal server error\"}}");
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertEquals("An error occurred while communicating with the Anthropic API. Please try again later.",
                callbacks.errors.get(0).getMessage());
    }
}
