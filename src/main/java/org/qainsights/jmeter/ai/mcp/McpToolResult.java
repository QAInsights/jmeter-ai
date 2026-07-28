package org.qainsights.jmeter.ai.mcp;

/**
 * The outcome of an MCP {@code tools/call}.
 * <p>
 * {@code isError} means the tool ran and reported a failure the model is expected to read
 * and react to (for example, a selector that matched nothing). Transport and protocol
 * failures raise {@link McpException} instead.
 *
 * @param isError whether the server flagged the call as failed
 * @param text    the flattened text content of the response; never null
 */
public record McpToolResult(boolean isError, String text) {

    public McpToolResult {
        text = text == null ? "" : text;
    }

    public static McpToolResult ok(String text) {
        return new McpToolResult(false, text);
    }

    public static McpToolResult error(String text) {
        return new McpToolResult(true, text);
    }
}
