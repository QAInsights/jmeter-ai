package org.qainsights.jmeter.ai.utils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class AnthropicGatewayRoutingTest {
    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> apiKeyHeaders = new CopyOnWriteArrayList<>();
    private final List<String> corpTokenHeaders = new CopyOnWriteArrayList<>();
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v1/messages", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            apiKeyHeaders.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            corpTokenHeaders.add(exchange.getRequestHeaders().getFirst("X-Corp-Token"));
            respond(exchange, """
                    {"id":"msg_test","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                     "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",
                     "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}
                    """);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void messageCompletionUsesGatewayUrlAndHeaders() {
        try (MockedStatic<AiConfig> ignored = mockConfig()) {
            AnthropicClient client = GatewayConfig.apply(AnthropicOkHttpClient.builder().apiKey("test-key")).build();
            MessageCreateParams params = MessageCreateParams.builder()
                    .model("claude-sonnet-4-6")
                    .maxTokens(16)
                    .addUserMessage("hello")
                    .build();

            Message message = client.messages().create(params);

            assertEquals("hello", message.content().get(0).asText().text());
            assertEquals(List.of("/v1/messages"), paths);
            assertEquals(List.of("test-key"), apiKeyHeaders);
            assertEquals(List.of("abc123"), corpTokenHeaders);
        }
    }

    private MockedStatic<AiConfig> mockConfig() {
        MockedStatic<AiConfig> mocked = mockStatic(AiConfig.class);
        mocked.when(() -> AiConfig.getProperty("anthropic.base.url", GatewayConfig.ANTHROPIC_DEFAULT_BASE_URL))
                .thenReturn(baseUrl);
        mocked.when(() -> AiConfig.getProperty("anthropic.extra.headers", ""))
                .thenReturn("X-Corp-Token=abc123");
        mocked.when(() -> AiConfig.getProperty("anthropic.models", ""))
                .thenReturn("");
        return mocked;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
