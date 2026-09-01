package org.qainsights.jmeter.ai.utils;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class OpenAiGatewayRoutingTest {
    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private final List<String> corpTokenHeaders = new CopyOnWriteArrayList<>();
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.createContext("/v1/chat/completions", exchange -> {
            recordRequest(exchange);
            respond(exchange, """
                    {"id":"chatcmpl-test","object":"chat.completion","created":1,
                     "model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant",
                     "content":"hello"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """);
        });
        server.createContext("/v1/models", exchange -> {
            recordRequest(exchange);
            respond(exchange, """
                    {"object":"list","data":[
                      {"id":"corp-gpt-4o","object":"model","created":1,"owned_by":"corp"},
                      {"id":"azure/gpt-4o","object":"model","created":1,"owned_by":"corp"},
                      {"id":"text-embedding-3-small","object":"model","created":1,"owned_by":"corp"}
                    ]}
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
    void chatCompletionUsesGatewayUrlAndHeaders() {
        try (MockedStatic<AiConfig> ignored = mockConfig("")) {
            OpenAIClient client = GatewayConfig.apply(OpenAIOkHttpClient.builder().apiKey("test-key")).build();
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model("corp-gpt-4o")
                    .addUserMessage("hello")
                    .maxCompletionTokens(16)
                    .build();

            ChatCompletion completion = client.chat().completions().create(params);

            assertEquals("hello", completion.choices().get(0).message().content().orElseThrow());
            assertEquals(List.of("/v1/chat/completions"), paths);
            assertEquals(List.of("Bearer test-key"), authorizationHeaders);
            assertEquals(List.of("abc123"), corpTokenHeaders);
        }
    }

    @Test
    void gatewayModelListingKeepsNonGptModelsButDropsEmbeddingModels() {
        try (MockedStatic<AiConfig> ignored = mockConfig("")) {
            List<String> modelIds = Models.getOpenAiModelIds(null);
            assertEquals(List.of("corp-gpt-4o", "azure/gpt-4o"), modelIds);
            assertEquals(List.of("/v1/models"), paths);
        }
    }

    @Test
    void configuredModelsAvoidModelListingRequest() {
        try (MockedStatic<AiConfig> ignored = mockConfig("corp-gpt-4o, azure/gpt-4o")) {
            assertEquals(List.of("corp-gpt-4o", "azure/gpt-4o"), Models.getOpenAiModelIds(null));
            assertTrue(paths.isEmpty());
        }
    }

    private MockedStatic<AiConfig> mockConfig(String models) {
        MockedStatic<AiConfig> mocked = mockStatic(AiConfig.class);
        mocked.when(() -> AiConfig.getProperty("openai.base.url", GatewayConfig.OPENAI_DEFAULT_BASE_URL))
                .thenReturn(baseUrl);
        mocked.when(() -> AiConfig.getProperty("openai.extra.headers", ""))
                .thenReturn("X-Corp-Token=abc123");
        mocked.when(() -> AiConfig.getProperty("openai.models", ""))
                .thenReturn(models);
        mocked.when(() -> AiConfig.getProperty("openai.api.key", "YOUR_API_KEY"))
                .thenReturn("test-key");
        return mocked;
    }

    private void recordRequest(HttpExchange exchange) {
        paths.add(exchange.getRequestURI().getPath());
        authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        corpTokenHeaders.add(exchange.getRequestHeaders().getFirst("X-Corp-Token"));
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
