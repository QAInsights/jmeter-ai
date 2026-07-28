package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpToolBridge}: MCP tools must become ordinary agent tools, and
 * neither a failing tool nor a dead server may escape as an exception.
 */
class McpToolBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records calls and returns scripted outcomes. */
    private static final class StubClient implements McpClient {
        private final List<McpTool> tools = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private Map<String, Object> lastArguments;
        private McpToolResult nextResult = McpToolResult.ok("done");
        private RuntimeException nextThrow;

        StubClient withTool(String name, String schemaJson) {
            try {
                tools.add(new McpTool(name, name + " description", MAPPER.readTree(schemaJson)));
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
            return this;
        }

        @Override
        public String initialize() {
            return StdioMcpClient.PROTOCOL_VERSION;
        }

        @Override
        public List<McpTool> listTools() {
            return tools;
        }

        @Override
        public McpToolResult callTool(String name, Map<String, Object> arguments) {
            calls.add(name);
            lastArguments = arguments;
            if (nextThrow != null) {
                throw nextThrow;
            }
            return nextResult;
        }

        @Override
        public void close() {
        }
    }

    private static final String URL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},"
                    + "\"required\":[\"url\"]}";

    @Test
    void should_wrapEveryAdvertisedTool() {
        StubClient client = new StubClient()
                .withTool("browser_navigate", URL_SCHEMA)
                .withTool("browser_snapshot", "{\"type\":\"object\"}");

        List<Tool> tools = new McpToolBridge(client).tools();

        assertEquals(2, tools.size());
        assertEquals("browser_navigate", tools.get(0).getSpec().getName());
        assertEquals("browser_snapshot", tools.get(1).getSpec().getName());
    }

    @Test
    void should_translateSchemaIntoSpec() {
        StubClient client = new StubClient().withTool("browser_navigate", URL_SCHEMA);

        ToolSpec spec = new McpToolBridge(client).tools().get(0).getSpec();

        assertEquals(1, spec.getParameters().size());
        assertEquals("url", spec.getParameters().get(0).getName());
        assertTrue(spec.getParameters().get(0).isRequired());
    }

    @Test
    void should_forwardArgumentsAndReturnOkResult() {
        StubClient client = new StubClient().withTool("browser_navigate", URL_SCHEMA);
        client.nextResult = McpToolResult.ok("### Page state\nTitle: Shop");

        Map<String, Object> args = new HashMap<>();
        args.put("url", "https://shop.test");
        ToolResult result = new McpToolBridge(client).tools().get(0).execute(args);

        assertTrue(result.isSuccess());
        assertEquals("### Page state\nTitle: Shop", result.getData());
        assertEquals(List.of("browser_navigate"), client.calls);
        assertEquals("https://shop.test", client.lastArguments.get("url"));
    }

    @Test
    void should_returnErrorResult_when_toolReportsFailure() {
        StubClient client = new StubClient().withTool("browser_click", "{\"type\":\"object\"}");
        client.nextResult = McpToolResult.error("ref e17 not found on the page");

        ToolResult result = new McpToolBridge(client).tools().get(0).execute(Map.of());

        assertFalse(result.isSuccess());
        assertEquals(McpToolBridge.ERR_MCP_TOOL_FAILED, result.getErrorCode());
        assertTrue(result.getMessage().contains("e17"),
                "the agent needs the detail to self-correct");
    }

    @Test
    void should_returnErrorResult_when_transportFails() {
        StubClient client = new StubClient().withTool("browser_click", "{\"type\":\"object\"}");
        client.nextThrow = new McpException("The MCP server closed its output stream");

        ToolResult result = new McpToolBridge(client).tools().get(0).execute(Map.of());

        assertFalse(result.isSuccess());
        assertEquals(McpToolBridge.ERR_MCP_TRANSPORT, result.getErrorCode());
        assertTrue(result.getMessage().contains("Do not retry"),
                "a dead server must not trigger a retry loop");
    }

    @Test
    void should_notLetTransportFailureEscapeThroughTheExecutor() {
        StubClient client = new StubClient().withTool("browser_click", "{\"type\":\"object\"}");
        client.nextThrow = new McpException("boom");

        ToolRegistry registry = new ToolRegistry();
        new McpToolBridge(client).registerInto(registry, null);

        ToolResult result = new ToolExecutor(registry).execute("browser_click", Map.of());

        assertFalse(result.isSuccess());
        assertEquals(McpToolBridge.ERR_MCP_TRANSPORT, result.getErrorCode(),
                "should be our specific code, not the executor's generic tool_exception");
    }

    @Test
    void should_registerAllTools() {
        StubClient client = new StubClient()
                .withTool("browser_navigate", URL_SCHEMA)
                .withTool("browser_snapshot", "{\"type\":\"object\"}");
        ToolRegistry registry = new ToolRegistry();

        assertEquals(2, new McpToolBridge(client).registerInto(registry, null));
        assertTrue(registry.isRegistered("browser_navigate"));
    }

    @Test
    void should_registerOnlyRequestedTools_when_allowListGiven() {
        StubClient client = new StubClient()
                .withTool("browser_navigate", URL_SCHEMA)
                .withTool("browser_install", "{\"type\":\"object\"}");
        ToolRegistry registry = new ToolRegistry();

        assertEquals(1, new McpToolBridge(client)
                .registerInto(registry, Set.of("browser_navigate")));
        assertFalse(registry.isRegistered("browser_install"));
    }

    @Test
    void should_skipRatherThanFail_when_nameAlreadyRegistered() {
        StubClient client = new StubClient().withTool("browser_navigate", URL_SCHEMA);
        ToolRegistry registry = new ToolRegistry();
        new McpToolBridge(client).registerInto(registry, null);

        assertEquals(0, new McpToolBridge(client).registerInto(registry, null),
                "a duplicate must not abort the session");
        assertEquals(1, registry.size());
    }

    @Test
    void should_rejectInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new McpToolBridge(null));
        assertThrows(IllegalArgumentException.class,
                () -> new McpToolBridge(new StubClient()).registerInto(null, null));
    }
}
