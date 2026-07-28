package org.qainsights.jmeter.ai.mcp;

import java.util.List;
import java.util.Map;

/**
 * A minimal Model Context Protocol client: enough to negotiate a session, discover the
 * server's tools, and call them.
 * <p>
 * Deliberately transport-agnostic. {@link StdioMcpClient} speaks newline-delimited
 * JSON-RPC over a child process's pipes, but tests substitute an in-memory implementation,
 * so nothing above this interface needs Node installed.
 */
public interface McpClient extends AutoCloseable {

    /**
     * Performs the MCP handshake and sends the {@code notifications/initialized} follow-up.
     * Must be called before {@link #listTools} or {@link #callTool}.
     *
     * @return the protocol version the server negotiated
     * @throws McpException if the handshake fails
     */
    String initialize();

    /**
     * @return every tool the server advertises, following pagination to the end
     * @throws McpException if the request fails
     */
    List<McpTool> listTools();

    /**
     * Invokes a tool.
     *
     * @param name      the tool name as advertised by {@link #listTools}
     * @param arguments argument map; may be null for a tool that takes none
     * @return the tool's result, which may itself be a reported error
     * @throws McpException on transport or protocol failure
     */
    McpToolResult callTool(String name, Map<String, Object> arguments);

    /** Releases the transport. Idempotent, and never throws. */
    @Override
    void close();
}
