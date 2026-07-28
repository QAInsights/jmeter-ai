package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * A tool advertised by an MCP server via {@code tools/list}.
 *
 * @param name        the tool name used in {@code tools/call}
 * @param description human-readable description shown to the model
 * @param inputSchema the raw JSON Schema for the tool's arguments; never null, but may be
 *                    an empty object for a tool that takes none
 */
public record McpTool(String name, String description, JsonNode inputSchema) {

    public McpTool {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("MCP tool name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? JsonNodeFactory.instance.objectNode() : inputSchema;
    }
}
