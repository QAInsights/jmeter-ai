package org.qainsights.jmeter.ai.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpServerTest {

    private static final String TOKEN = "a".repeat(64);
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private McpHttpServer server;
    private URI uri;

    @BeforeEach
    void start() throws Exception {
        McpTestTools tools = new McpTestTools();
        server = McpHttpServer.start(0, TOKEN,
                new McpServerProtocol(tools.registry, Set.of("delete_element"), null, "1.0"));
        uri = URI.create("http://127.0.0.1:" + server.port() + McpHttpServer.PATH);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validRequestGetsJson() throws Exception {
        HttpResponse<String> response = send(post(INITIALIZE).header("Authorization", "Bearer " + TOKEN));
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("feather-wand", json.readTree(response.body()).path("result").path("serverInfo")
                .path("name").asText());
    }

    @Test
    void toolCallRoundTrip() throws Exception {
        HttpResponse<String> response = send(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\",\"count\":1}}}")
                .header("Authorization", "Bearer " + TOKEN)
                .header("MCP-Protocol-Version", "2025-06-18"));
        JsonNode result = json.readTree(response.body()).path("result");
        assertEquals("hi x1", result.path("content").get(0).path("text").asText());
    }

    @Test
    void missingOrWrongTokenIsUnauthorized() throws Exception {
        HttpResponse<String> none = send(post(INITIALIZE));
        assertEquals(401, none.statusCode());
        assertTrue(none.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
        assertEquals(401, send(post(INITIALIZE).header("Authorization", "Bearer " + "b".repeat(64))).statusCode());
        assertEquals(401, send(post(INITIALIZE).header("Authorization", TOKEN)).statusCode());
    }

    @Test
    void foreignBrowserOriginsAreRefusedEvenWithTheToken() throws Exception {
        assertEquals(403, send(post(INITIALIZE).header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "https://evil.example")).statusCode());
        assertEquals(200, send(post(INITIALIZE).header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "http://localhost:6274")).statusCode());
    }

    @Test
    void notificationsAreAccepted() throws Exception {
        HttpResponse<String> response = send(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .header("Authorization", "Bearer " + TOKEN));
        assertEquals(202, response.statusCode());
        assertTrue(response.body().isEmpty());
    }

    @Test
    void getAndDeleteAreNotOffered() throws Exception {
        HttpResponse<String> get = send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + TOKEN).GET());
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElse(""));
        assertEquals(405, send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + TOKEN).DELETE())
                .statusCode());
    }

    @Test
    void badBodiesAndVersionsAreRejected() throws Exception {
        HttpResponse<String> parse = send(post("{not json").header("Authorization", "Bearer " + TOKEN));
        assertEquals(400, parse.statusCode());
        assertEquals(-32700, json.readTree(parse.body()).path("error").path("code").asInt());
        assertEquals(400, send(post(INITIALIZE).header("Authorization", "Bearer " + TOKEN)
                .header("MCP-Protocol-Version", "1999-01-01")).statusCode());
        assertEquals(404, send(HttpRequest.newBuilder(uri.resolve("/mcp/extra"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))).statusCode());
    }

    @Test
    void oversizedBodiesAreRejected() throws Exception {
        String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
                + "x".repeat(McpHttpServer.MAX_BODY_BYTES) + "\"}}";
        assertEquals(413, send(post(big).header("Authorization", "Bearer " + TOKEN)).statusCode());
    }

    @Test
    void localOriginCheck() {
        assertTrue(McpHttpServer.isLocalOrigin("http://localhost:3000"));
        assertTrue(McpHttpServer.isLocalOrigin("http://127.0.0.1"));
        assertTrue(McpHttpServer.isLocalOrigin("http://[::1]:8080"));
        assertFalse(McpHttpServer.isLocalOrigin("http://localhost.evil.example"));
        assertFalse(McpHttpServer.isLocalOrigin("null"));
        assertFalse(McpHttpServer.isLocalOrigin("file://localhost"));
        assertFalse(McpHttpServer.isLocalOrigin("http://%zz"));
    }

    @Test
    void listensOnLoopbackOnlyAndNeedsAToken() {
        assertEquals("127.0.0.1", uri.getHost());
        assertThrows(IllegalArgumentException.class, () -> McpHttpServer.start(0, " ", null));
    }
}
