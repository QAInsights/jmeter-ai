package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport tests for {@link StdioMcpClient}, driven by an in-memory {@link FakeMcpServer}.
 */
class StdioMcpClientTest {

    private static final String INIT_RESULT =
            "{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"playwright\"}}";

    private static String route(JsonNode request) {
        String method = request.path("method").asText();
        switch (method) {
            case "initialize":
                return FakeMcpServer.resultFor(request, INIT_RESULT);
            case "tools/list":
                return FakeMcpServer.resultFor(request,
                        "{\"tools\":[{\"name\":\"browser_navigate\",\"description\":\"Go to a URL\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                                + "{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}}]}");
            case "tools/call":
                return FakeMcpServer.resultFor(request,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"navigated\"}]}");
            default:
                return null;
        }
    }

    @Test
    void should_negotiateProtocolAndSendInitializedNotification() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(StdioMcpClientTest::route);
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            assertEquals("2025-06-18", client.initialize());

            assertTrue(server.awaitRequests(2, 2_000), "expected initialize + initialized");
            assertEquals("initialize", server.received().get(0).path("method").asText());

            JsonNode notification = server.received().get(1);
            assertEquals("notifications/initialized", notification.path("method").asText());
            assertFalse(notification.has("id"), "a JSON-RPC notification must carry no id");
        }
    }

    @Test
    void should_sendClientInfoAndProtocolVersion_when_initializing() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(StdioMcpClientTest::route);
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            client.initialize();

            JsonNode params = server.received().get(0).path("params");
            assertEquals(StdioMcpClient.PROTOCOL_VERSION, params.path("protocolVersion").asText());
            assertEquals("feather-wand", params.path("clientInfo").path("name").asText());
        }
    }

    @Test
    void should_listTools() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(StdioMcpClientTest::route);
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            List<McpTool> tools = client.listTools();

            assertEquals(1, tools.size());
            assertEquals("browser_navigate", tools.get(0).name());
            assertEquals("Go to a URL", tools.get(0).description());
            assertEquals("string",
                    tools.get(0).inputSchema().path("properties").path("url").path("type").asText());
        }
    }

    @Test
    void should_followPagination_when_serverReturnsCursor() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(request -> {
            if (!"tools/list".equals(request.path("method").asText())) {
                return null;
            }
            boolean firstPage = request.path("params").path("cursor").isMissingNode();
            return firstPage
                    ? FakeMcpServer.resultFor(request,
                            "{\"tools\":[{\"name\":\"a\"}],\"nextCursor\":\"page2\"}")
                    : FakeMcpServer.resultFor(request, "{\"tools\":[{\"name\":\"b\"}]}");
        });
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            List<McpTool> tools = client.listTools();

            assertEquals(2, tools.size(), "both pages should be collected");
            assertEquals("a", tools.get(0).name());
            assertEquals("b", tools.get(1).name());
        }
    }

    @Test
    void should_callToolAndFlattenTextContent() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(StdioMcpClientTest::route);
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            Map<String, Object> args = new HashMap<>();
            args.put("url", "https://shop.test");
            McpToolResult result = client.callTool("browser_navigate", args);

            assertFalse(result.isError());
            assertEquals("navigated", result.text());

            JsonNode params = server.received().get(0).path("params");
            assertEquals("browser_navigate", params.path("name").asText());
            assertEquals("https://shop.test", params.path("arguments").path("url").asText());
        }
    }

    @Test
    void should_joinMultipleTextParts_and_noteNonTextParts() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(request -> FakeMcpServer.resultFor(request,
                "{\"content\":[{\"type\":\"text\",\"text\":\"line1\"},"
                        + "{\"type\":\"image\",\"data\":\"...\"},"
                        + "{\"type\":\"text\",\"text\":\"line2\"}]}"));
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            McpToolResult result = client.callTool("browser_take_screenshot", null);

            assertEquals("line1\n[image content omitted]\nline2", result.text(),
                    "a dropped part must be visible, not silently missing");
        }
    }

    @Test
    void should_reportToolReportedFailure_when_isErrorSet() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(request -> FakeMcpServer.resultFor(request,
                "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"no such element\"}]}"));
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            McpToolResult result = client.callTool("browser_click", null);

            assertTrue(result.isError());
            assertEquals("no such element", result.text());
        }
    }

    @Test
    void should_throw_when_serverReturnsJsonRpcError() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(
                request -> FakeMcpServer.errorFor(request, -32601, "Method not found"));
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            McpException e = assertThrows(McpException.class, client::listTools);
            assertTrue(e.getMessage().contains("Method not found"));
            assertTrue(e.getMessage().contains("-32601"));
        }
    }

    @Test
    void should_timeOut_when_serverNeverResponds() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(request -> null);
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput(), 200)) {

            McpException e = assertThrows(McpException.class, client::listTools);
            assertTrue(e.getMessage().contains("did not respond"));
        }
    }

    @Test
    void should_failFast_when_serverDiesMidRequest() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(request -> {
            throw new IllegalStateException("unreachable");
        })) {
            // Long timeout: the test only passes if EOF wakes the caller, not the timeout.
            try (StdioMcpClient client =
                         new StdioMcpClient(server.clientInput(), server.clientOutput(), 60_000)) {
                new Thread(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    server.closeOutput();
                }).start();

                long start = System.currentTimeMillis();
                assertThrows(McpException.class, client::listTools);
                assertTrue(System.currentTimeMillis() - start < 10_000,
                        "a dead server must surface promptly, not after the request timeout");
            }
        }
    }

    @Test
    void should_ignoreNonJsonAndNotificationLines() throws Exception {
        AtomicReference<FakeMcpServer> ref = new AtomicReference<>();
        // Servers print banners on stdout and interleave notifications with responses;
        // neither must break request/response matching.
        FakeMcpServer server = new FakeMcpServer(request -> {
            FakeMcpServer self = ref.get();
            self.send("Debugger listening on ws://127.0.0.1:9229");
            self.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                    + "\"params\":{\"level\":\"info\"}}");
            return FakeMcpServer.resultFor(request, "{\"tools\":[]}");
        });
        ref.set(server);

        try (server;
             StdioMcpClient client =
                     new StdioMcpClient(server.clientInput(), server.clientOutput(), 5_000)) {
            assertTrue(client.listTools().isEmpty(), "noise must not break response matching");
        }
    }

    @Test
    void should_matchResponsesById_when_serverRepliesOutOfOrder() throws Exception {
        // Reply with a wrong id first: a client keying on arrival order would mis-route it.
        try (FakeMcpServer server = new FakeMcpServer(request -> {
            return "{\"jsonrpc\":\"2.0\",\"id\":9999,\"result\":{\"tools\":[{\"name\":\"ghost\"}]}}\n"
                    + FakeMcpServer.resultFor(request, "{\"tools\":[{\"name\":\"real\"}]}");
        });
             StdioMcpClient client =
                     new StdioMcpClient(server.clientInput(), server.clientOutput(), 5_000)) {

            List<McpTool> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("real", tools.get(0).name(), "the unmatched id must be ignored");
        }
    }

    @Test
    void should_rejectInvalidConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new StdioMcpClient(null, System.out));
        assertThrows(IllegalArgumentException.class,
                () -> new StdioMcpClient(System.in, null));
        assertThrows(IllegalArgumentException.class,
                () -> new StdioMcpClient(System.in, System.out, 0));
    }

    @Test
    void should_rejectBlankToolName() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(StdioMcpClientTest::route);
             StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput())) {

            assertThrows(IllegalArgumentException.class, () -> client.callTool("  ", null));
        }
    }

    @Test
    void should_beIdempotentAndRefuseUse_when_closed() throws Exception {
        FakeMcpServer server = new FakeMcpServer(StdioMcpClientTest::route);
        StdioMcpClient client = new StdioMcpClient(server.clientInput(), server.clientOutput());

        client.close();
        assertDoesNotThrow(client::close);

        McpException e = assertThrows(McpException.class, client::listTools);
        assertTrue(e.getMessage().contains("closed"));
        server.close();
    }
}
