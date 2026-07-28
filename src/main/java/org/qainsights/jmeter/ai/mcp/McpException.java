package org.qainsights.jmeter.ai.mcp;

/**
 * Signals a failure in the MCP transport or protocol: the server could not be started,
 * the connection dropped, a request timed out, or the server returned a JSON-RPC error.
 * <p>
 * Distinct from a tool that ran and reported failure - that is a normal
 * {@link McpToolResult} with {@code isError} set, not an exception.
 */
public class McpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
