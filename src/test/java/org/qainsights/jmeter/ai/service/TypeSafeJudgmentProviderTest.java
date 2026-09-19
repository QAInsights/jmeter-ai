package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeSafeJudgmentProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private AtomicReference<String> authorization;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void startServer() throws IOException {
        authorization = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void chooseSendsSystemOneRequestAndParsesChoice() throws Exception {
        respond(200, """
                {"model":"jev-latest","answers":{"route":{"type":"choice","choice":"EDIT",
                "probabilities":{"EDIT":0.85,"RUN":0.15},"confidence":0.7}},
                "usage":{"input_tokens":12,"output_tokens":4}}
                """);
        TypeSafeJudgmentProvider provider = provider();

        JudgmentProvider.ChoiceAnswer answer = provider.choose(
                Map.of("message", "change the thread group"), "Choose a route",
                Map.of("EDIT", "Modify the plan", "RUN", "Run the plan"));

        assertEquals("EDIT", answer.choice());
        assertEquals(0.85, answer.probabilityOf("EDIT"));
        assertEquals(0.7, answer.confidence());
        assertEquals("Bearer secret-key", authorization.get());
        JsonNode sent = MAPPER.readTree(requestBody.get());
        assertEquals("jev-test", sent.path("model").asText());
        assertEquals("change the thread group", sent.path("state").path("message").asText());
        assertEquals("choice", sent.path("questions").path("route").path("type").asText());
    }

    @Test
    void capabilitiesExposeOnlyImplementedChoiceJudgments() {
        assertEquals(1, provider().capabilities().size());
        assertTrue(provider().capabilities().contains(JudgmentProvider.Capability.CHOICE));
        assertFalse(provider().capabilities().contains(JudgmentProvider.Capability.NOUL));
        assertFalse(provider().capabilities().contains(JudgmentProvider.Capability.SCORE));
    }

    @Test
    void publicProvidersReuseTheSharedHttpClient() throws Exception {
        java.lang.reflect.Field field = TypeSafeJudgmentProvider.class.getDeclaredField("client");
        field.setAccessible(true);

        assertSame(field.get(provider()), field.get(provider()));
    }

    @Test
    void chooseDefaultsOmittedProbabilitiesToZero() {
        respond(200, "{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"A\","
                + "\"probabilities\":{\"A\":1.0},\"confidence\":1.0}}}");

        JudgmentProvider.ChoiceAnswer answer = provider().choose(
                "state", "route", Map.of("A", "one", "B", "two"));

        assertEquals(1.0, answer.probabilityOf("A"));
        assertEquals(0.0, answer.probabilityOf("B"));
    }

    @Test
    void chooseRejectsInvalidInputsBeforeNetworkCall() {
        TypeSafeJudgmentProvider provider = provider();

        assertThrows(TypeSafeJudgmentProvider.JudgmentException.class,
                () -> provider.choose("state", "", Map.of("A", "one", "B", "two")));
        assertThrows(TypeSafeJudgmentProvider.JudgmentException.class,
                () -> provider.choose("state", "route", Map.of("A", "one")));
    }

    @Test
    void chooseDoesNotExposeErrorResponseBody() {
        respond(401, "credential details must not escape");

        TypeSafeJudgmentProvider.JudgmentException error = assertThrows(
                TypeSafeJudgmentProvider.JudgmentException.class,
                () -> provider().choose("state", "route", Map.of("A", "one", "B", "two")));

        assertEquals("TypeSafe request failed with HTTP 401", error.getMessage());
        assertFalse(error.getMessage().contains("credential"));
    }

    @Test
    void chooseRejectsMalformedOrUnknownAnswers() {
        respond(200, "{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"OTHER\","
                + "\"probabilities\":{\"A\":0.5,\"B\":0.5},\"confidence\":0.1}}}");

        assertThrows(TypeSafeJudgmentProvider.JudgmentException.class,
                () -> provider().choose("state", "route", Map.of("A", "one", "B", "two")));
    }

    @Test
    void endpointAcceptsBaseOrFullSystemOneUrl() {
        assertEquals("https://api.typesafe.ai/v1/systemone",
                TypeSafeJudgmentProvider.endpoint("https://api.typesafe.ai/").toString());
        assertEquals("https://gateway.test/v1/systemone",
                TypeSafeJudgmentProvider.endpoint("https://gateway.test/v1/systemone").toString());
    }

    @Test
    void endpointNeverDuplicatesTheV1Segment() {
        for (String base : new String[] {
                "https://api.typesafe.ai/v1",
                "https://api.typesafe.ai/v1/",
                "https://api.typesafe.ai/v1//",
                "https://api.typesafe.ai/v1/systemone",
                "https://api.typesafe.ai/v1/systemone/" }) {
            assertEquals("https://api.typesafe.ai/v1/systemone",
                    TypeSafeJudgmentProvider.endpoint(base).toString(), base);
        }
        assertEquals("https://api.typesafe.ai/v1/systemone",
                TypeSafeJudgmentProvider.endpoint(null).toString());
        assertEquals("https://api.typesafe.ai/v1/systemone",
                TypeSafeJudgmentProvider.endpoint("   ").toString());
    }

    private TypeSafeJudgmentProvider provider() {
        return new TypeSafeJudgmentProvider("secret-key", baseUrl(), "jev-test", Duration.ofSeconds(2));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(int status, String body) {
        server.createContext("/v1/systemone", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
