package org.qainsights.jmeter.ai.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts the tools of an {@link McpClient} into the plugin's own {@link Tool} abstraction,
 * so browser control reaches the model through exactly the same path as the JMeter tools -
 * one registry, one {@code AgentLoop}, both providers, no special-casing.
 * <p>
 * The two failure modes are kept distinct on purpose:
 * <ul>
 *   <li>a tool that ran and reported failure becomes a {@link ToolResult} error the agent
 *       can read and self-correct from (e.g. "no element matches that ref");</li>
 *   <li>a transport or protocol failure ({@link McpException}) also becomes a
 *       {@code ToolResult} error rather than a thrown exception, because an escaping
 *       throwable would abort the whole recording session. It is logged at error level so
 *       a dead server is still diagnosable.</li>
 * </ul>
 */
public final class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    public static final String ERR_MCP_TOOL_FAILED = "mcp_tool_failed";
    public static final String ERR_MCP_TRANSPORT = "mcp_transport_error";

    private final McpClient client;

    public McpToolBridge(McpClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.client = client;
    }

    /**
     * Wraps every tool the server advertises.
     *
     * @return adapters in the order the server listed them
     */
    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        for (McpTool mcpTool : client.listTools()) {
            tools.add(toTool(mcpTool));
        }
        return tools;
    }

    /**
     * Registers every advertised tool, skipping any whose name is already taken.
     * <p>
     * Skipping rather than failing keeps a name clash in a third-party server from
     * breaking the whole session; the collision is logged as a warning.
     *
     * @param registry the registry to populate
     * @param only     if non-empty, only these tool names are registered
     * @return the number of tools registered
     */
    public int registerInto(ToolRegistry registry, Collection<String> only) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        int registered = 0;
        for (Tool tool : tools()) {
            String name = tool.getSpec().getName();
            if (only != null && !only.isEmpty() && !only.contains(name)) {
                continue;
            }
            if (registry.isRegistered(name)) {
                log.warn("Skipping MCP tool '{}': a tool with that name is already registered", name);
                continue;
            }
            registry.register(tool);
            registered++;
        }
        return registered;
    }

    /** Wraps a single MCP tool. */
    public Tool toTool(McpTool mcpTool) {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(mcpTool);
        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return call(spec.getName(), arguments);
            }
        };
    }

    private ToolResult call(String name, Map<String, Object> arguments) {
        try {
            McpToolResult result = client.callTool(name, arguments);
            if (result.isError()) {
                return ToolResult.error(ERR_MCP_TOOL_FAILED, result.text());
            }
            return ToolResult.ok(result.text());
        } catch (McpException e) {
            log.error("MCP transport failure calling '{}'", name, e);
            return ToolResult.error(ERR_MCP_TRANSPORT,
                    "The browser automation server is unreachable: " + e.getMessage()
                            + ". Do not retry; the recording session must be restarted.");
        }
    }
}
