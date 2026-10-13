package org.qainsights.jmeter.ai.mcpserver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qainsights.jmeter.ai.agent.tool.JsonSchemaMapper;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Transport-independent MCP server logic: answers JSON-RPC 2.0 messages for
 * {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call},
 * exposing the Agent Mode tool registry to external MCP clients.
 */
public final class McpServerProtocol {

    private static final Logger log = LoggerFactory.getLogger(McpServerProtocol.class);

    public static final String LATEST_VERSION = "2025-06-18";
    public static final List<String> SUPPORTED_VERSIONS = List.of(LATEST_VERSION, "2025-03-26", "2024-11-05");

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    static final String SERVER_NAME = "feather-wand";

    static final String INSTRUCTIONS = "Feather Wand drives the Apache JMeter test plan that is open in the "
            + "user's JMeter GUI. Call get_tree_state first: every element_id is a tree path taken from it, and "
            + "ids change after renames, moves and deletes, so read the tree again after structural edits. "
            + "Use get_element_schema before add_element or update_element_property to learn valid property keys. "
            + "delete_element, move_element, open_plan and apply_correlation may wait for the user to approve a "
            + "dialog in JMeter; if a call is declined, do not retry it. get_test_results runs the plan and "
            + "blocks until it finishes. Edits are not written to disk until save_plan is called.";

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            ReadToolHandlers.GET_TREE_STATE, ReadToolHandlers.GET_ELEMENT_CONFIG,
            ReadToolHandlers.GET_ELEMENT_CHILDREN, ReadToolHandlers.GET_ELEMENT_SCHEMA);

    private static final String STOP_TOOL = "stop_test";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper json = new ObjectMapper();
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final Set<String> destructiveTools;
    private final String serverVersion;
    private final ReentrantLock toolLock = new ReentrantLock(true);
    long busyWaitMillis = 5000;

    public McpServerProtocol(ToolRegistry registry, Set<String> destructiveTools,
            ToolConfirmationGate confirmationGate, String serverVersion) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.destructiveTools = destructiveTools == null ? Collections.emptySet() : destructiveTools;
        this.executor = new ToolExecutor(registry, this.destructiveTools, confirmationGate);
        this.serverVersion = serverVersion == null ? "unknown" : serverVersion;
    }

    /**
     * Handles one message or batch. Returns {@code null} when nothing must be sent back,
     * which is the case for notifications and for responses sent by the client.
     */
    public JsonNode handle(JsonNode message) {
        if (message != null && message.isArray()) {
            if (message.isEmpty()) {
                return error(null, INVALID_REQUEST, "Empty batch");
            }
            ArrayNode replies = json.createArrayNode();
            for (JsonNode item : message) {
                JsonNode reply = handleOne(item);
                if (reply != null) {
                    replies.add(reply);
                }
            }
            return replies.isEmpty() ? null : replies;
        }
        return handleOne(message);
    }

    /** JSON-RPC error reply for a body that could not be parsed. */
    public JsonNode parseError() {
        return error(null, PARSE_ERROR, "Parse error: the body is not valid JSON");
    }

    private JsonNode handleOne(JsonNode message) {
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            return error(null, INVALID_REQUEST, "Not a JSON-RPC 2.0 message");
        }
        JsonNode id = message.get("id");
        if (!message.hasNonNull("method")) {
            return null;
        }
        if (id == null || id.isNull()) {
            return null;
        }
        if (!id.isTextual() && !id.isIntegralNumber()) {
            return error(null, INVALID_REQUEST, "The id must be a string or an integer");
        }
        String method = message.path("method").asText();
        JsonNode params = message.path("params");
        try {
            JsonNode result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> json.createObjectNode();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params);
                default -> throw new RpcException(METHOD_NOT_FOUND, "Method not found: " + method);
            };
            ObjectNode reply = envelope(id);
            reply.set("result", result);
            return reply;
        } catch (RpcException e) {
            return error(id, e.code, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP request '{}' failed", method, e);
            return error(id, INTERNAL_ERROR, "Internal error");
        }
    }

    private JsonNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", SUPPORTED_VERSIONS.contains(requested) ? requested : LATEST_VERSION);
        ObjectNode tools = result.putObject("capabilities").putObject("tools");
        tools.put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("title", "Feather Wand for Apache JMeter");
        info.put("version", serverVersion);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private JsonNode listTools() {
        ObjectNode result = json.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (ToolSpec spec : registry.getSpecs()) {
            tools.add(describe(spec));
        }
        return result;
    }

    ObjectNode describe(ToolSpec spec) {
        ObjectNode tool = json.createObjectNode();
        tool.put("name", spec.getName());
        tool.put("description", description(spec));
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.set("properties", json.valueToTree(JsonSchemaMapper.properties(spec)));
        List<String> required = JsonSchemaMapper.required(spec);
        if (!required.isEmpty()) {
            schema.set("required", json.valueToTree(required));
        }
        boolean readOnly = READ_ONLY_TOOLS.contains(spec.getName());
        ObjectNode annotations = tool.putObject("annotations");
        annotations.put("readOnlyHint", readOnly);
        if (!readOnly) {
            annotations.put("destructiveHint", destructiveTools.contains(spec.getName()));
        }
        return tool;
    }

    private static String description(ToolSpec spec) {
        String text = spec.getDescription() == null ? "" : spec.getDescription();
        if (spec.getPreconditions().isEmpty()) {
            return text;
        }
        return text + "\nPreconditions: " + String.join("; ", spec.getPreconditions());
    }

    private JsonNode callTool(JsonNode params) {
        String name = params.path("name").isTextual() ? params.path("name").asText() : null;
        if (name == null || name.isEmpty()) {
            throw new RpcException(INVALID_PARAMS, "tools/call needs a tool name");
        }
        if (!registry.isRegistered(name)) {
            throw new RpcException(INVALID_PARAMS, "Unknown tool: " + name);
        }
        JsonNode arguments = params.get("arguments");
        Map<String, Object> args;
        if (arguments == null || arguments.isNull()) {
            args = Collections.emptyMap();
        } else if (arguments.isObject()) {
            args = json.convertValue(arguments, MAP_TYPE);
        } else {
            throw new RpcException(INVALID_PARAMS, "arguments must be a JSON object");
        }
        Telemetry.record(TelemetryFeature.MCP_TOOL_CALL);
        ToolResult outcome = execute(name, args);
        ObjectNode result = json.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        if (outcome.isSuccess()) {
            content.put("text", outcome.getData());
            result.put("isError", false);
        } else {
            content.put("text", outcome.getErrorCode() + ": " + outcome.getMessage());
            result.put("isError", true);
        }
        return result;
    }

    /**
     * Read tools and stop_test run at once. Every other call holds one lock from node lookup to mutation,
     * so a second client cannot replace or reshape the tree under it; a caller that cannot get the lock
     * in time gets a "busy" tool error instead of tying up a worker thread.
     */
    private ToolResult execute(String name, Map<String, Object> args) {
        if (READ_ONLY_TOOLS.contains(name) || STOP_TOOL.equals(name)) {
            return executor.execute(name, args);
        }
        boolean locked;
        try {
            locked = toolLock.tryLock(busyWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            return ToolResult.error("busy", "Another Feather Wand tool call is still running, for example "
                    + "get_test_results or a confirmation dialog in JMeter. Retry once it finishes; "
                    + "call stop_test to end a running test.");
        }
        try {
            return executor.execute(name, args);
        } finally {
            toolLock.unlock();
        }
    }

    private ObjectNode envelope(JsonNode id) {
        ObjectNode reply = json.createObjectNode();
        reply.put("jsonrpc", "2.0");
        if (id == null) {
            reply.putNull("id");
        } else {
            reply.set("id", id);
        }
        return reply;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode reply = envelope(id);
        ObjectNode error = reply.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return reply;
    }

    private static final class RpcException extends RuntimeException {
        private final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
