package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider.JudgmentException;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class OllamaEmbeddingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void embedPostsModelAndInputsAndParsesVectorsInOrder() throws Exception {
        respond(200, "{\"model\":\"embeddinggemma\",\"embeddings\":[[0.1,0.2],[0.3,-0.4]]}");

        List<float[]> vectors = client("embeddinggemma").embed(List.of("first", "second"));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{0.1f, 0.2f}, vectors.get(0));
        assertArrayEquals(new float[]{0.3f, -0.4f}, vectors.get(1));
        JsonNode sent = MAPPER.readTree(requestBody.get());
        assertEquals("embeddinggemma", sent.path("model").asText());
        assertEquals("first", sent.path("input").get(0).asText());
        assertEquals("second", sent.path("input").get(1).asText());
    }

    @Test
    void missingModelSaysHowToPullIt() {
        respond(404, "{\"error\":\"model \\\"embeddinggemma\\\" not found, try pulling it first\"}");

        JudgmentException e = assertThrows(JudgmentException.class,
                () -> client("embeddinggemma").embed(List.of("x")));

        assertTrue(e.getMessage().contains("ollama pull embeddinggemma"), e.getMessage());
        assertEquals(404, e.httpCode());
    }

    @Test
    void modelNotFoundTextPreservesTheActualHttpStatus() {
        respond(400, "{\"error\":\"model \\\"embeddinggemma\\\" not found\"}");

        JudgmentException e = assertThrows(JudgmentException.class,
                () -> client("embeddinggemma").embed(List.of("x")));

        assertEquals(400, e.httpCode());
        assertEquals(OllamaEmbeddingClient.modelUnavailableMessage("embeddinggemma"), e.getMessage());
    }

    @Test
    void modelUnavailableMessageUsesTheNormalizedModel() {
        String expected = OllamaEmbeddingClient.modelUnavailableMessage(OllamaEmbeddingClient.DEFAULT_MODEL);

        assertEquals(expected, OllamaEmbeddingClient.modelUnavailableMessage(null));
        assertEquals(expected, OllamaEmbeddingClient.modelUnavailableMessage(""));
        assertEquals(expected, OllamaEmbeddingClient.modelUnavailableMessage("  embeddinggemma  "));
    }

    @Test
    void serverErrorsAndMalformedResponsesThrow() {
        respond(500, "{\"error\":\"this model does not support embeddings\"}");
        JudgmentException http = assertThrows(JudgmentException.class, () -> client("llama3.1").embed(List.of("x")));
        assertTrue(http.getMessage().contains("HTTP 500"), http.getMessage());
        assertTrue(http.getMessage().contains("does not support embeddings"), http.getMessage());

        server.removeContext("/api/embed");
        respond(200, "{\"embeddings\":[[0.1]]}");
        assertThrows(JudgmentException.class, () -> client("m").embed(List.of("a", "b")));

        server.removeContext("/api/embed");
        respond(200, "{\"embeddings\":[[\"x\"]]}");
        assertThrows(JudgmentException.class, () -> client("m").embed(List.of("a")));

        server.removeContext("/api/embed");
        respond(200, "not json");
        assertThrows(JudgmentException.class, () -> client("m").embed(List.of("a")));
    }

    @Test
    void unreachableServerSaysOllamaIsNotRunning() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OllamaEmbeddingClient client = new OllamaEmbeddingClient("http://127.0.0.1:" + port, "m", Duration.ofSeconds(2));

        JudgmentException e = assertThrows(JudgmentException.class, () -> client.embed(List.of("x")));

        assertTrue(e.getMessage().contains("not reachable"), e.getMessage());
    }

    @Test
    void emptyInputIsRejectedWithoutARequest() {
        assertThrows(JudgmentException.class, () -> client("m").embed(List.of()));
        assertEquals(null, requestBody.get());
    }

    @Test
    void fromConfigReusesOllamaHostAndPortAndDefaultsTheModel() {
        try (MockedStatic<AiConfig> config = mockStatic(AiConfig.class, CALLS_REAL_METHODS)) {
            config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            config.when(() -> AiConfig.getProperty("ollama.host", "http://localhost")).thenReturn("http://127.0.0.1");
            config.when(() -> AiConfig.getProperty("ollama.port", "11434"))
                    .thenReturn(String.valueOf(server.getAddress().getPort()));
            config.when(() -> AiConfig.getProperty(OllamaEmbeddingClient.MODEL_KEY, OllamaEmbeddingClient.DEFAULT_MODEL))
                    .thenReturn("  ");
            respond(200, "{\"embeddings\":[[1.0]]}");

            OllamaEmbeddingClient client = OllamaEmbeddingClient.fromConfig(Duration.ofSeconds(5));

            assertEquals(OllamaEmbeddingClient.DEFAULT_MODEL, client.model());
            assertEquals(1, client.embed(List.of("x")).size());
        }
    }

    private OllamaEmbeddingClient client(String model) {
        return new OllamaEmbeddingClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", model,
                Duration.ofSeconds(5));
    }

    private void respond(int status, String body) {
        server.createContext("/api/embed", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
