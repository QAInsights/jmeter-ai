package org.qainsights.jmeter.ai.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerProtocolTest {

    private final ObjectMapper json = new ObjectMapper();
    private final McpTestTools tools = new McpTestTools();
    private boolean allow = true;
    private final McpServerProtocol protocol = new McpServerProtocol(tools.registry,
            Set.of("delete_element"), (name, args) -> allow, "9.9.9");

    private JsonNode send(String message) throws Exception {
        return protocol.handle(json.readTree(message));
    }

    private JsonNode call(String name, String arguments) throws Exception {
        return send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\"" + (arguments == null ? "" : ",\"arguments\":" + arguments) + "}}");
    }

    @Test
    void initializeEchoesASupportedVersionAndDescribesTheServer() throws Exception {
        JsonNode reply = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        JsonNode result = reply.path("result");
        assertEquals(1, reply.path("id").asInt());
        assertEquals("2025-03-26", result.path("protocolVersion").asText());
        assertFalse(result.path("capabilities").path("tools").path("listChanged").asBoolean(true));
        assertEquals("feather-wand", result.path("serverInfo").path("name").asText());
        assertEquals("9.9.9", result.path("serverInfo").path("version").asText());
        assertTrue(result.path("instructions").asText().contains("get_tree_state"));
    }

    @Test
    void initializeOffersTheLatestVersionForAnUnknownOne() throws Exception {
        JsonNode reply = send("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals(McpServerProtocol.LATEST_VERSION, reply.path("result").path("protocolVersion").asText());
        assertEquals("a", reply.path("id").asText());
    }

    @Test
    void notificationsAndClientResponsesGetNoReply() throws Exception {
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertNull(send("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"));
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"}}"));
        assertTrue(tools.calls.isEmpty());
    }

    @Test
    void pingAnswersWithAnEmptyResult() throws Exception {
        JsonNode reply = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        assertTrue(reply.path("result").isObject());
        assertTrue(reply.path("result").isEmpty());
    }

    @Test
    void toolsListMapsSchemasAndSafetyHints() throws Exception {
        JsonNode list = send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}").path("result").path("tools");
        assertEquals(4, list.size());

        JsonNode read = list.get(0);
        assertEquals("get_tree_state", read.path("name").asText());
        assertEquals("object", read.path("inputSchema").path("type").asText());
        assertFalse(read.path("inputSchema").has("required"));
        assertTrue(read.path("annotations").path("readOnlyHint").asBoolean());

        JsonNode delete = list.get(1);
        assertEquals("element_id", delete.path("inputSchema").path("required").get(0).asText());
        assertEquals("string", delete.path("inputSchema").path("properties").path("element_id").path("type").asText());
        assertFalse(delete.path("annotations").path("readOnlyHint").asBoolean(true));
        assertTrue(delete.path("annotations").path("destructiveHint").asBoolean());

        JsonNode echo = list.get(2);
        assertEquals("integer", echo.path("inputSchema").path("properties").path("count").path("type").asText());
        assertFalse(echo.path("annotations").path("destructiveHint").asBoolean(true));
        assertTrue(echo.path("description").asText().endsWith("Preconditions: A plan is open"));
    }

    @Test
    void toolsCallReturnsTextContent() throws Exception {
        JsonNode result = call("echo", "{\"text\":\"hi\",\"count\":2}").path("result");
        assertFalse(result.path("isError").asBoolean(true));
        assertEquals("text", result.path("content").get(0).path("type").asText());
        assertEquals("hi x2", result.path("content").get(0).path("text").asText());
    }

    @Test
    void toolFailuresAreToolErrorsNotProtocolErrors() throws Exception {
        JsonNode reply = call("echo", "{\"text\":\"fail\"}");
        assertFalse(reply.has("error"));
        assertTrue(reply.path("result").path("isError").asBoolean());
        assertEquals("bad_text: Text was fail", reply.path("result").path("content").get(0).path("text").asText());

        JsonNode missing = call("echo", "{}").path("result");
        assertTrue(missing.path("isError").asBoolean());
        assertTrue(missing.path("content").get(0).path("text").asText().startsWith("missing_parameter"));
    }

    @Test
    void declinedDestructiveCallsNeverRun() throws Exception {
        allow = false;
        JsonNode result = call("delete_element", "{\"element_id\":\"Test Plan/Thread Group\"}").path("result");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().startsWith("declined_by_user"));
        assertFalse(tools.calls.contains("delete_element"));

        allow = true;
        JsonNode allowed = call("delete_element", "{\"element_id\":\"Test Plan/Thread Group\"}").path("result");
        assertEquals("deleted Test Plan/Thread Group", allowed.path("content").get(0).path("text").asText());
    }

    @Test
    void aSecondEditWaitsForTheRunningCallThenGetsBusy() throws Exception {
        protocol.busyWaitMillis = 50;
        CompletableFuture<JsonNode> run = CompletableFuture.supplyAsync(() -> {
            try {
                return call("get_test_results", "{}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(tools.started.await(5, TimeUnit.SECONDS));

        JsonNode busy = call("echo", "{\"text\":\"hi\"}").path("result");
        assertTrue(busy.path("isError").asBoolean());
        assertTrue(busy.path("content").get(0).path("text").asText().startsWith("busy:"));
        assertFalse(tools.calls.contains("echo"));

        JsonNode read = call("get_tree_state", "{}").path("result");
        assertFalse(read.path("isError").asBoolean(true));

        tools.release.countDown();
        assertEquals("done", run.get(5, TimeUnit.SECONDS).path("result").path("content").get(0).path("text").asText());
        assertFalse(call("echo", "{\"text\":\"hi\"}").path("result").path("isError").asBoolean(true));
    }

    @Test
    void badToolCallsAreInvalidParams() throws Exception {
        assertEquals(-32602, call("nope", "{}").path("error").path("code").asInt());
        assertEquals(-32602, call("echo", "[1]").path("error").path("code").asInt());
        assertEquals(-32602, send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{}}")
                .path("error").path("code").asInt());
        assertTrue(tools.calls.isEmpty());
    }

    @Test
    void unknownMethodsAndMalformedMessagesAreRejected() throws Exception {
        assertEquals(-32601, send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/list\"}")
                .path("error").path("code").asInt());
        JsonNode notRpc = send("{\"id\":6,\"method\":\"ping\"}");
        assertEquals(-32600, notRpc.path("error").path("code").asInt());
        assertTrue(notRpc.path("id").isNull());
        assertEquals(-32600, send("{\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"ping\"}")
                .path("error").path("code").asInt());
        assertEquals(-32700, protocol.parseError().path("error").path("code").asInt());
    }

    @Test
    void batchesAnswerEachRequest() throws Exception {
        JsonNode replies = send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}]");
        assertEquals(2, replies.size());
        assertEquals(1, replies.get(0).path("id").asInt());
        assertEquals(-32601, replies.get(1).path("error").path("code").asInt());
        assertEquals(-32600, send("[]").path("error").path("code").asInt());
        assertNull(send("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"));
    }
}
