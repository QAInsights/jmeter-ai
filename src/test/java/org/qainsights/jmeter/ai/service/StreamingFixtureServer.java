package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local {@link HttpServer} fixture that replays canned SSE / NDJSON / JSON
 * bodies for provider streaming tests and records every request it receives.
 */
final class StreamingFixtureServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A request captured by the fixture. */
    record RecordedRequest(String method, String path, String query, Map<String, List<String>> headers,
                           String body) {

        JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("Request body is not JSON: " + body, e);
            }
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private record CannedResponse(int status, String contentType, String body) {
    }

    private final HttpServer server;
    private final Map<String, CannedResponse> responses = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    StreamingFixtureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code http://127.0.0.1:<port>} without a trailing slash. */
    String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int port() {
        return server.getAddress().getPort();
    }

    StreamingFixtureServer respondSse(String path, String sseBody) {
        return respond(path, 200, "text/event-stream", sseBody);
    }

    StreamingFixtureServer respondNdjson(String path, String ndjsonBody) {
        return respond(path, 200, "application/x-ndjson", ndjsonBody);
    }

    StreamingFixtureServer respondJson(String path, int status, String jsonBody) {
        return respond(path, status, "application/json", jsonBody);
    }

    StreamingFixtureServer respond(String path, int status, String contentType, String body) {
        responses.put(path, new CannedResponse(status, contentType, body));
        return this;
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    RecordedRequest onlyRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("Expected exactly one request but got " + requests.size() + ": " + requests);
        }
        return requests.get(0);
    }

    /** OpenAI-style SSE: one {@code data:} frame per JSON chunk, then {@code data: [DONE]}. */
    static String openAiSse(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            sb.append("data: ").append(chunk).append("\n\n");
        }
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    /** Plain {@code data:} SSE frames without a terminal sentinel (Gemini). */
    static String dataSse(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            sb.append("data: ").append(chunk).append("\n\n");
        }
        return sb.toString();
    }

    /** Named-event SSE (Anthropic Messages, OpenAI Responses): {@code event:} name/JSON pairs. */
    static String namedSse(String... nameThenData) {
        if (nameThenData.length % 2 != 0) {
            throw new IllegalArgumentException("Expected event name/data pairs");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nameThenData.length; i += 2) {
            sb.append("event: ").append(nameThenData[i]).append('\n')
                    .append("data: ").append(nameThenData[i + 1]).append("\n\n");
        }
        return sb.toString();
    }

    /** Newline-delimited JSON (Ollama). */
    static String ndjson(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String path = exchange.getRequestURI().getPath();
        requests.add(new RecordedRequest(exchange.getRequestMethod(), path,
                exchange.getRequestURI().getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()), body));

        CannedResponse response = responses.get(path);
        if (response == null) {
            response = new CannedResponse(404, "application/json",
                    "{\"error\":{\"message\":\"no fixture for " + path + "\"}}");
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
