package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.usage.UsageStats;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenRouterAiServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "test-openrouter-key";
    private static final String SYSTEM_PROMPT = "You are an OpenRouter test assistant.";

    private HttpServer server;
    private final AtomicInteger modelRequests = new AtomicInteger();
    private final AtomicReference<String> modelPath = new AtomicReference<>();
    private final AtomicReference<String> modelQuery = new AtomicReference<>();
    private final AtomicReference<String> modelAuthorization = new AtomicReference<>();
    private final AtomicReference<String> modelReferer = new AtomicReference<>();
    private final AtomicReference<String> modelTitle = new AtomicReference<>();
    private final AtomicReference<Integer> modelStatus = new AtomicReference<>(200);
    private final AtomicReference<String> modelResponse = new AtomicReference<>("{\"data\":[]}");
    private final AtomicReference<JsonNode> completionRequest = new AtomicReference<>();
    private final AtomicReference<String> completionReferer = new AtomicReference<>();
    private final AtomicReference<String> completionTitle = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void listsFeaturedModelsFirstAndFiltersBatchAndMissingFeaturedModels() {
        modelResponse.set("""
                {"data":[
                  {"id":"remaining-first"},
                  {"id":"featured-a"},
                  {"id":"hidden:batch"},
                  {"id":"featured-b"},
                  {"id":"default-model"},
                  {"id":"remaining-last"}
                ]}
                """);
        OpenRouterAiService service = service("default-model",
                List.of("featured-b", "missing-featured", "featured-a"));

        List<String> models = service.listModels();

        assertEquals(List.of("featured-b", "featured-a", "default-model",
                "remaining-first", "remaining-last"), models);
        assertEquals(models, service.listModels());
        assertEquals(1, modelRequests.get());
        assertEquals("/api/v1/models", modelPath.get());
        assertEquals("supported_parameters=tools", modelQuery.get());
        assertEquals("Bearer " + API_KEY, modelAuthorization.get());
        assertEquals(OpenRouterAiService.REFERER_VALUE, modelReferer.get());
        assertEquals(OpenRouterAiService.TITLE_VALUE, modelTitle.get());
    }

    @Test
    void retriesCatalogDiscoveryAfterServerError() {
        modelStatus.set(500);
        OpenRouterAiService service = service("default-model",
                List.of("featured-a", "featured-b"));

        assertEquals(List.of("featured-a", "featured-b", "default-model"), service.listModels());
        assertEquals(1, modelRequests.get());

        modelStatus.set(200);
        modelResponse.set("{\"data\":[{\"id\":\"live-model\"}]}");
        assertEquals(List.of("live-model"), service.listModels());
        assertEquals(2, modelRequests.get());
    }

    @Test
    void returnsNoModelsWithoutAClientOrMakingARequest() {
        OpenRouterAiService service = serviceWithoutClient();

        assertEquals(List.of(), service.listModels());
        assertEquals(List.of(), service.featuredModelIds());
        assertEquals(0, modelRequests.get());
    }

    @Test
    void exposesFeaturedModelIdsWithPrefixAndDeduplicatesDefaultInOrder() {
        OpenRouterAiService defaultLast = service("default-model",
                List.of("featured-a", "featured-b", "featured-a"));
        assertEquals(List.of(
                "openrouter:featured-a",
                "openrouter:featured-b",
                "openrouter:default-model"), defaultLast.featuredModelIds());
        defaultLast.setModel("selected-model");
        assertEquals(List.of(
                "openrouter:featured-a",
                "openrouter:featured-b",
                "openrouter:default-model"), defaultLast.featuredModelIds());

        OpenRouterAiService defaultFeatured = service("default-model",
                List.of("featured-a", "default-model", "featured-b"));
        assertEquals(List.of(
                "openrouter:featured-a",
                "openrouter:default-model",
                "openrouter:featured-b"), defaultFeatured.featuredModelIds());
    }

    @Test
    void reportsMissingClientFromStreamOnTheEventDispatchThread() throws InterruptedException {
        OpenRouterAiService service = serviceWithoutClient();
        CountDownLatch errorReceived = new CountDownLatch(1);
        AtomicReference<Exception> streamError = new AtomicReference<>();
        AtomicBoolean calledOnEdt = new AtomicBoolean();

        String syncError = service.generateResponse(List.of("Hello"), "model");
        service.generateStreamResponse(List.of("Hello"), "model", ignored -> { }, () -> { }, error -> {
            streamError.set(error);
            calledOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
            errorReceived.countDown();
        });

        assertTrue(errorReceived.await(5, TimeUnit.SECONDS));
        assertTrue(streamError.get() instanceof IllegalStateException);
        assertEquals(syncError.substring("Error: ".length()), streamError.get().getMessage());
        assertTrue(calledOnEdt.get());
    }

    @Test
    void sendsAttributionHeadersAndBareModelIdOnChatCompletion() throws Exception {
        UsageStats usage = mock(UsageStats.class);
        OpenRouterAiService service = service("anthropic/claude-sonnet-5.5", List.of());
        service.setUsageStats(usage);

        assertEquals("OpenRouter response",
                service.generateResponse(List.of("Hello"), "anthropic/claude-sonnet-5.5"));

        assertEquals("anthropic/claude-sonnet-5.5", completionRequest.get().path("model").asText());
        assertEquals("Bearer " + API_KEY, completionRequest.get().path("authorization").asText());
        assertEquals(OpenRouterAiService.REFERER_VALUE, completionReferer.get());
        assertEquals(OpenRouterAiService.TITLE_VALUE, completionTitle.get());
        verify(usage).record("openrouter:anthropic/claude-sonnet-5.5", 12, 5);
    }

    @Test
    void omitsTemperatureOnlyForOpenAiReasoningModelsInSyncAndStreamingRequests() throws Exception {
        OpenRouterAiService service = service("default-model", List.of());

        service.generateResponse(List.of("Hello"), "openai/o3");
        assertFalse(completionRequest.get().has("temperature"));
        service.generateResponse(List.of("Hello"), "anthropic/claude-sonnet-5.5");
        assertTrue(completionRequest.get().has("temperature"));

        assertStreamTemperature(service, "openai/o3", false);
        assertStreamTemperature(service, "anthropic/claude-sonnet-5.5", true);
    }

    @Test
    void normalizesOpenRouterChatBaseUrls() {
        assertEquals("https://openrouter.ai/api/v1",
                OpenRouterAiService.chatBaseUrl("https://openrouter.ai/api"));
        assertEquals("https://openrouter.ai/api/v1",
                OpenRouterAiService.chatBaseUrl("https://openrouter.ai/api/"));
        assertEquals("https://openrouter.ai/api/v1",
                OpenRouterAiService.chatBaseUrl("https://openrouter.ai/api/v1"));
        assertEquals("https://openrouter.ai/api/v1",
                OpenRouterAiService.chatBaseUrl("https://openrouter.ai/api/alpha/decisions"));
        assertEquals(OpenRouterAiService.DEFAULT_BASE_URL, OpenRouterAiService.chatBaseUrl(""));
        assertEquals("https://proxy.example.com/openrouter/v1",
                OpenRouterAiService.chatBaseUrl("https://proxy.example.com/openrouter/v1"));
    }

    private OpenRouterAiService service(String defaultModel, List<String> featuredModels) {
        String baseUrl = origin() + "/api/v1";
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(API_KEY)
                .baseUrl(baseUrl)
                .putHeader(OpenRouterAiService.REFERER_HEADER, OpenRouterAiService.REFERER_VALUE)
                .putHeader(OpenRouterAiService.TITLE_HEADER, OpenRouterAiService.TITLE_VALUE)
                .maxRetries(0)
                .build();
        return new OpenRouterAiService(client, API_KEY, baseUrl, defaultModel,
                0.7f, 10, 4096L, SYSTEM_PROMPT, featuredModels, HttpClient.newHttpClient());
    }

    private OpenRouterAiService serviceWithoutClient() {
        return new OpenRouterAiService(null, null, origin() + "/api/v1", "default-model",
                0.7f, 10, 4096L, SYSTEM_PROMPT, List.of("featured-model"),
                HttpClient.newHttpClient());
    }

    private void assertStreamTemperature(OpenRouterAiService service, String model, boolean expected)
            throws InterruptedException {
        CountDownLatch streamFinished = new CountDownLatch(1);
        AtomicReference<Exception> streamError = new AtomicReference<>();

        service.generateStreamResponse(List.of("Hello"), model, ignored -> { },
                streamFinished::countDown, error -> {
                    streamError.set(error);
                    streamFinished.countDown();
                });

        assertTrue(streamFinished.await(5, TimeUnit.SECONDS));
        assertNull(streamError.get());
        assertEquals(expected, completionRequest.get().has("temperature"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/models")) {
            modelRequests.incrementAndGet();
            modelPath.set(path);
            modelQuery.set(exchange.getRequestURI().getRawQuery());
            modelAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            modelReferer.set(exchange.getRequestHeaders().getFirst(OpenRouterAiService.REFERER_HEADER));
            modelTitle.set(exchange.getRequestHeaders().getFirst(OpenRouterAiService.TITLE_HEADER));
            send(exchange, modelStatus.get(), modelResponse.get());
            return;
        }
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        completionReferer.set(exchange.getRequestHeaders().getFirst(OpenRouterAiService.REFERER_HEADER));
        completionTitle.set(exchange.getRequestHeaders().getFirst(OpenRouterAiService.TITLE_HEADER));
        ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("authorization",
                exchange.getRequestHeaders().getFirst("Authorization"));
        completionRequest.set(request);
        if (request.path("stream").asBoolean()) {
            sendStream(exchange);
            return;
        }
        send(exchange, 200, """
                {"id":"chatcmpl-test","object":"chat.completion","created":1,
                 "model":"anthropic/claude-sonnet-5.5",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"OpenRouter response"},
                   "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
                """);
    }

    private static void sendStream(HttpExchange exchange) throws IOException {
        byte[] bytes = ("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"OpenRouter response\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
