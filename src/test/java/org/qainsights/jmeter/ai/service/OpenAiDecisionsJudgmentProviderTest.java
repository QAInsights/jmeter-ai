package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiDecisionsJudgmentProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> CRITERIA = criteria();

    private HttpServer server;
    private AtomicReference<String> path;
    private AtomicReference<String> authorization;
    private AtomicReference<String> requestBody;
    private AtomicReference<Integer> status;
    private AtomicReference<String> responseBody;

    @BeforeEach
    void startServer() throws IOException {
        path = new AtomicReference<>();
        authorization = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        status = new AtomicReference<>(200);
        responseBody = new AtomicReference<>("{}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/decisions", exchange -> {
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
    void chooseSendsChoiceQuestionAndParsesAnswer() throws Exception {
        respond(200, """
                {"id":"dec_1","object":"decision","model":"gpt-6-luna","answers":[
                {"type":"choice","name":"route","choice":"EDIT","confidence":0.7,
                "probabilities":[{"value":"EDIT","probability":0.85},{"value":"RUN","probability":0.15}]}],
                "usage":{"input_tokens":12}}
                """);

        JudgmentProvider.ChoiceAnswer answer = provider("gpt-6-luna").choose(
                Map.of("message", "change the thread group"), "Choose a route", CRITERIA);

        assertEquals("EDIT", answer.choice());
        assertEquals(0.85, answer.probabilityOf("EDIT"));
        assertEquals(0.15, answer.probabilityOf("RUN"));
        assertEquals(0.7, answer.confidence());
        assertEquals("/v1/decisions", path.get());
        assertEquals("Bearer secret-key", authorization.get());

        JsonNode request = MAPPER.readTree(requestBody.get());
        assertEquals("gpt-6-luna", request.path("model").asText());
        assertEquals("change the thread group",
                MAPPER.readTree(request.path("input").asText()).path("message").asText());
        JsonNode question = request.path("questions").get(0);
        assertEquals("choice", question.path("type").asText());
        assertEquals(OpenAiDecisionsJudgmentProvider.QUESTION_NAME, question.path("name").asText());
        assertEquals("Choose a route", question.path("instructions").asText());
        assertEquals("EDIT", question.path("choices").get(0).path("value").asText());
        assertEquals("Modify the plan", question.path("choices").get(0).path("description").asText());
        assertEquals("RUN", question.path("choices").get(1).path("value").asText());
    }

    @Test
    void blankModelFallsBackToDefaultAndMissingProbabilitiesAreZero() throws Exception {
        respond(200, """
                {"answers":[{"type":"choice","name":"route","choice":"RUN","confidence":0.9,
                "probabilities":[{"value":"RUN","probability":0.9},{"value":"OTHER","probability":0.1}]}]}
                """);

        JudgmentProvider.ChoiceAnswer answer = provider(" ").choose(Map.of("message", "go"), "Choose", CRITERIA);

        assertEquals("RUN", answer.choice());
        assertEquals(0.0, answer.probabilityOf("EDIT"));
        assertEquals(Set.of("EDIT", "RUN"), answer.probabilities().keySet());
        assertEquals(OpenAiDecisionsJudgmentProvider.DEFAULT_MODEL,
                MAPPER.readTree(requestBody.get()).path("model").asText());
    }

    @Test
    void capabilitiesAreChoiceOnly() {
        assertEquals(Set.of(JudgmentProvider.Capability.CHOICE), provider(null).capabilities());
    }

    @Test
    void refusalUnknownChoiceAndInvalidNumbersAreRejected() {
        assertRejected("""
                {"answers":[{"type":"refusal","name":"route","refusal":"cannot help"}]}
                """, "declined");
        assertRejected("""
                {"answers":[]}
                """, "no answers");
        assertRejected("""
                {"answers":[{"type":"choice","name":"route","choice":"DELETE","confidence":0.9,"probabilities":[]}]}
                """, "unknown choice");
        assertRejected("""
                {"answers":[{"type":"choice","name":"route","choice":true,"confidence":0.9,"probabilities":[]}]}
                """, "unknown choice");
        assertRejected("""
                {"answers":[{"type":"choice","name":"route","choice":"EDIT","confidence":1.5,"probabilities":[]}]}
                """, "invalid confidence");
        assertRejected("""
                {"answers":[{"type":"choice","name":"route","choice":"EDIT","confidence":0.9,
                "probabilities":[{"value":"EDIT","probability":-0.1}]}]}
                """, "invalid probabilities");
        assertRejected("""
                {"answers":[{"type":"choice","name":"route","choice":"EDIT"}]}
                """, "malformed");
    }

    @Test
    void httpErrorsAndBadArgumentsThrow() {
        respond(401, "{\"error\":{\"message\":\"bad key\"}}");
        OpenAiDecisionsJudgmentProvider provider = provider(null);
        OpenAiDecisionsJudgmentProvider.DecisionsException error = assertThrows(
                OpenAiDecisionsJudgmentProvider.DecisionsException.class,
                () -> provider.choose(Map.of("message", "x"), "Choose", CRITERIA));
        assertTrue(error.getMessage().contains("request failed"));

        assertThrows(OpenAiDecisionsJudgmentProvider.DecisionsException.class,
                () -> provider.choose(Map.of(), "", CRITERIA));
        assertThrows(OpenAiDecisionsJudgmentProvider.DecisionsException.class,
                () -> provider.choose(Map.of(), "Choose", Map.of("ONLY", "one option")));
    }

    private void assertRejected(String body, String expectedMessage) {
        respond(200, body);
        OpenAiDecisionsJudgmentProvider.DecisionsException error = assertThrows(
                OpenAiDecisionsJudgmentProvider.DecisionsException.class,
                () -> provider(null).choose(Map.of("message", "x"), "Choose", CRITERIA));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    private OpenAiDecisionsJudgmentProvider provider(String model) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new OpenAiDecisionsJudgmentProvider(OpenAIOkHttpClient.builder()
                .apiKey("secret-key").baseUrl(baseUrl).maxRetries(0).build().decisions(),
                model, Duration.ofSeconds(5));
    }

    private void respond(int code, String body) {
        status.set(code);
        responseBody.set(body);
    }

    private static Map<String, String> criteria() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("EDIT", "Modify the plan");
        criteria.put("RUN", "Run the plan");
        return criteria;
    }
}
