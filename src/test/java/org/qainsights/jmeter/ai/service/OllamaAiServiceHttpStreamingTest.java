package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ollama4j.exceptions.OllamaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.usage.UsageStats;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Drives {@link OllamaAiService#generateStreamResponse} against a local
 * {@code /api/chat} NDJSON fixture.
 */
class OllamaAiServiceHttpStreamingTest {

    private static final String SYSTEM_PROMPT = "You are an Ollama test assistant.";
    private static final String CHAT = "/api/chat";
    private static final String MODEL = "qwen3:4b";

    private StreamingFixtureServer fixture;
    private MockedStatic<AiConfig> aiConfig;
    private OllamaAiService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StreamingFixtureServer();
        String port = String.valueOf(fixture.port());
        aiConfig = mockStatic(AiConfig.class);
        aiConfig.when(() -> AiConfig.getProperty(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            switch (key) {
                case "ollama.host": return "http://127.0.0.1";
                case "ollama.port": return port;
                case "ollama.default.model": return MODEL;
                case "ollama.temperature": return "0.3";
                case "ollama.max.history.size": return "10";
                case "ollama.thinking.mode": return "ENABLED";
                case "ollama.thinking.level": return "MEDIUM";
                case "ollama.request.timeout.seconds": return "20";
                case "ollama.system.prompt": return SYSTEM_PROMPT;
                default: return invocation.getArgument(1);
            }
        });
        service = new OllamaAiService();
    }

    @AfterEach
    void tearDown() {
        aiConfig.close();
        fixture.close();
    }

    private static String line(String thinking, String content, boolean done) {
        return "{\"model\":\"" + MODEL + "\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\""
                + (thinking == null ? "" : ",\"thinking\":\"" + thinking + "\"") + "},\"done\":" + done
                + (done ? ",\"done_reason\":\"stop\",\"total_duration\":1,\"load_duration\":1,"
                        + "\"prompt_eval_count\":19,\"prompt_eval_duration\":1,\"eval_count\":6,\"eval_duration\":1"
                        : "")
                + "}";
    }

    @Test
    void routesThinkingAndContentRecordsUsageAndCompletes() throws Exception {
        fixture.respondNdjson(CHAT, StreamingFixtureServer.ndjson(
                line("Pick a timer.", "", false),
                line(null, "Use a ", false),
                line(null, "Constant Timer.", false),
                line(null, "", true)));
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("Add a sampler", "Added HTTP sampler", "Now add a timer"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();

        assertTrue(callbacks.errors.isEmpty(), () -> "unexpected errors: " + callbacks.errors);
        assertEquals("Pick a timer.", String.join("", callbacks.reasoning));
        assertEquals("Use a Constant Timer.", callbacks.text());
        assertEquals(1, callbacks.completions.get());
        verify(usage).record("ollama:" + MODEL, 19, 6);

        StreamingFixtureServer.RecordedRequest request = fixture.onlyRequest();
        assertEquals("POST", request.method());
        JsonNode body = request.json();
        assertEquals(MODEL, body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        List<String> roles = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        body.get("messages").forEach(m -> {
            roles.add(m.get("role").asText());
            contents.add(m.get("content").asText());
        });
        assertEquals(List.of("system", "user", "assistant", "user"), roles);
        assertEquals(List.of(SYSTEM_PROMPT, "Add a sampler", "Added HTTP sampler", "Now add a timer"), contents);
    }

    @Test
    void serverErrorFiresOnErrorExactlyOnceAndNeverCompletes() throws Exception {
        fixture.respondJson(CHAT, 500, "{\"error\":\"model runner has unexpectedly stopped\"}");
        UsageStats usage = mock(UsageStats.class);
        service.setUsageStats(usage);
        StreamCallbacks callbacks = new StreamCallbacks();

        service.generateStreamResponse(List.of("hi"), null,
                callbacks.onToken, callbacks.onReasoning, callbacks.onComplete, callbacks.onError);
        callbacks.awaitTerminal();
        // Give a stray second terminal callback time to land before asserting exactly-once.
        Thread.sleep(200);
        StreamCallbacks.flushEdt();

        assertEquals(1, callbacks.errors.size());
        assertEquals(0, callbacks.completions.get());
        assertTrue(callbacks.tokens.isEmpty());
        assertTrue(callbacks.reasoning.isEmpty());
        assertInstanceOf(OllamaException.class, callbacks.errors.get(0));
        assertEquals("model runner has unexpectedly stopped", callbacks.errors.get(0).getMessage());
        verify(usage, never()).record(anyString(), anyLong(), anyLong());
    }
}
