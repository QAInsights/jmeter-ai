package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterDecisionsJudgmentProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> CRITERIA = Map.of("EDIT", "Modify the plan", "RUN", "Run the plan");

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void choosePostsToAlphaDecisionsWithMicrosoftDecisionByDefault() throws Exception {
        responseBody.set("""
                {"id":"gen-1","model":"microsoft/microsoft-decision-1","provider":"Azure",
                "answers":{"route":{"type":"choice","choice":"RUN",
                "probabilities":{"EDIT":0.1,"RUN":0.9},"confidence":0.8}},
                "usage":{"input_tokens":40,"output_tokens":0}}
                """);

        JudgmentProvider.ChoiceAnswer answer = provider(null).choose(
                Map.of("message", "run the test"), "Choose a route", CRITERIA);

        assertEquals("RUN", answer.choice());
        assertEquals(0.9, answer.probabilityOf("RUN"));
        assertEquals(0.8, answer.confidence());
        assertEquals("/api/alpha/decisions", path.get());
        assertEquals("Bearer sk-or-test", authorization.get());
        JsonNode sent = MAPPER.readTree(requestBody.get());
        assertEquals(OpenRouterDecisionsJudgmentProvider.DEFAULT_MODEL, sent.path("model").asText());
        assertEquals("run the test", sent.path("state").path("message").asText());
        JsonNode route = sent.path("questions").path("route");
        assertEquals("choice", route.path("type").asText());
        assertEquals("Choose a route", route.path("instructions").asText());
        assertEquals("Run the plan", route.path("criteria").path("RUN").asText());
    }

    @Test
    void configuredModelIsSent() throws Exception {
        responseBody.set("""
                {"model":"m","answers":{"route":{"type":"choice","choice":"EDIT",
                "probabilities":{"EDIT":0.7,"RUN":0.3},"confidence":0.6}},"usage":{"input_tokens":1,"output_tokens":0}}
                """);

        provider(" typesafe/jev-1.13 ").choose("edit it", "Choose a route", CRITERIA);

        assertEquals("typesafe/jev-1.13", MAPPER.readTree(requestBody.get()).path("model").asText());
    }

    @Test
    void httpErrorsAndBadAnswersNameOpenRouter() {
        status.set(401);
        RuntimeException unauthorized = assertThrows(RuntimeException.class,
                () -> provider(null).choose("x", "Choose a route", CRITERIA));
        assertEquals("OpenRouter request failed with HTTP 401", unauthorized.getMessage());

        status.set(200);
        responseBody.set("""
                {"model":"m","answers":{"route":{"type":"choice","choice":"DANCE","confidence":0.9}},
                "usage":{"input_tokens":1,"output_tokens":0}}
                """);
        RuntimeException unknown = assertThrows(RuntimeException.class,
                () -> provider(null).choose("x", "Choose a route", CRITERIA));
        assertEquals("OpenRouter response contained an unknown choice", unknown.getMessage());
    }

    @Test
    void blankKeyFailsWithoutSendingARequest() {
        OpenRouterDecisionsJudgmentProvider provider = new OpenRouterDecisionsJudgmentProvider(
                HttpClient.newHttpClient(), " ", endpointUri(), null, Duration.ofSeconds(5));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> provider.choose("x", "Choose a route", CRITERIA));
        assertTrue(error.getMessage().contains("OpenRouter API key"), error.getMessage());
        assertEquals(null, path.get());
    }

    @Test
    void endpointAcceptsRootHostV1OrFullUrl() {
        String expected = "https://openrouter.ai/api/alpha/decisions";
        assertEquals(expected, OpenRouterDecisionsJudgmentProvider.endpoint(null).toString());
        assertEquals(expected, OpenRouterDecisionsJudgmentProvider.endpoint("  ").toString());
        assertEquals(expected, OpenRouterDecisionsJudgmentProvider.endpoint("https://openrouter.ai").toString());
        assertEquals(expected, OpenRouterDecisionsJudgmentProvider.endpoint("https://openrouter.ai/api/").toString());
        assertEquals(expected, OpenRouterDecisionsJudgmentProvider.endpoint("https://openrouter.ai/api/v1").toString());
        assertEquals(expected, OpenRouterDecisionsJudgmentProvider.endpoint(expected + "/").toString());
    }

    private URI endpointUri() {
        return OpenRouterDecisionsJudgmentProvider.endpoint("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void scoreUsesTheSameEndpointAndParsesTheScoreAnswer() throws Exception {
        responseBody.set("""
                {"id":"gen-2","model":"microsoft/microsoft-decision-1",
                "answers":{"severity":{"type":"score","score":0.4,"confidence":0.7,
                "probabilities":{"0":0.6,"1":0.4},"legend":{"0":"Minor","1":"Major"}}}}
                """);
        Map<String, String> levels = new java.util.LinkedHashMap<>();
        levels.put("Low", "Minor");
        levels.put("High", "Major");

        JudgmentProvider.ScoreAnswer answer = provider(null).score(Map.of("total_failures", 2), "Rate", levels);

        assertEquals(0, answer.level(2));
        assertEquals(0.7, answer.confidence());
        assertEquals("/api/alpha/decisions", path.get());
        assertEquals("score", MAPPER.readTree(requestBody.get()).path("questions").path("severity")
                .path("type").asText());
    }

    private OpenRouterDecisionsJudgmentProvider provider(String model) {
        return new OpenRouterDecisionsJudgmentProvider(HttpClient.newHttpClient(), "sk-or-test", endpointUri(), model,
                Duration.ofSeconds(5));
    }
}
