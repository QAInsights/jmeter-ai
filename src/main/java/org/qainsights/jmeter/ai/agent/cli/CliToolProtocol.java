package org.qainsights.jmeter.ai.agent.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.tool.JsonSchemaMapper;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Text protocol that lets a CLI-backed model (Codex, Claude Code) participate in
 * the same tool-calling {@link org.qainsights.jmeter.ai.agent.loop.AgentLoop} as
 * the SDK providers. Those CLIs expose no tool-calling API, so the tools are
 * described in the prompt and the model is asked to answer with a single JSON
 * object:
 *
 * <pre>
 * {"tool_calls":[{"name":"add_element","arguments":{...}}]}
 * {"final":"...answer..."}
 * </pre>
 *
 * A reply that is not parseable JSON is treated as a final plain-text answer, so
 * a model that ignores the protocol still ends the run with something useful
 * instead of failing the loop.
 */
public final class CliToolProtocol {

    private static final Logger log = LoggerFactory.getLogger(CliToolProtocol.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private CliToolProtocol() {
    }

    /** The protocol instructions appended to the system prompt. */
    public static String instructions(List<ToolSpec> specs) {
        StringBuilder text = new StringBuilder();
        text.append("You are driving a JMeter GUI through tools. Reply with exactly one JSON object and ")
                .append("no prose, no markdown and no code fences.\n")
                .append("To call tools: {\"tool_calls\":[{\"name\":\"<tool>\",\"arguments\":{...}}]}\n")
                .append("When the work is done: {\"final\":\"<your answer to the user>\"}\n")
                .append("Call at most a few tools per reply; you will be given each tool's result and asked again.\n")
                .append("Do not invent tools and do not run shell commands: only the tools below exist.\n")
                .append("Available tools (JSON schema):\n")
                .append(renderTools(specs));
        return text.toString();
    }

    /** The tool specs as a JSON array of {@code {name, description, parameters, preconditions}}. */
    static String renderTools(List<ToolSpec> specs) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec spec : specs) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", JsonSchemaMapper.properties(spec));
            schema.put("required", JsonSchemaMapper.required(spec));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", spec.getName());
            tool.put("description", spec.getDescription());
            tool.put("parameters", schema);
            if (!spec.getPreconditions().isEmpty()) {
                tool.put("preconditions", spec.getPreconditions());
            }
            tools.add(tool);
        }
        try {
            return JSON.writeValueAsString(tools);
        } catch (JacksonException e) {
            log.error("Could not render the tool specs for the CLI agent protocol", e);
            return "[]";
        }
    }

    /**
     * Parses one CLI reply into a neutral assistant turn.
     *
     * @param callIdPrefix prefix for the synthetic tool-call ids (the CLIs do not
     *                     supply any), e.g. {@code "call_3"}
     */
    public static AssistantTurn parse(String reply, String callIdPrefix) {
        String text = reply == null ? "" : reply.trim();
        JsonNode root = readObject(text);
        if (root == null) {
            return new AssistantTurn(text, List.of());
        }
        List<AssistantTurn.ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = root.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
            int index = 0;
            for (JsonNode call : toolCalls) {
                JsonNode name = call.get("name");
                if (name == null || name.asText("").isEmpty()) {
                    log.warn("Ignoring a tool call without a name from the CLI agent reply");
                    continue;
                }
                calls.add(new AssistantTurn.ToolCall(callIdPrefix + "_" + index++, name.asText(),
                        toArguments(call.get("arguments"))));
            }
        }
        String message = firstText(root, "final", "message", "text", "thought");
        if (calls.isEmpty() && message.isEmpty()) {
            // Valid JSON, but nothing we understand: treat it as the answer.
            return new AssistantTurn(text, List.of());
        }
        return new AssistantTurn(message, calls);
    }

    /** The outermost JSON object in the reply, tolerating code fences and stray prose. */
    private static JsonNode readObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            log.debug("CLI agent reply was not JSON, treating it as a final answer");
            return null;
        }
    }

    private static Map<String, Object> toArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return Map.of();
        }
        try {
            if (arguments.isTextual()) {
                // Some models double-encode the arguments object as a string.
                return JSON.readValue(arguments.asText("{}"), MAP_TYPE);
            }
            return JSON.convertValue(arguments, MAP_TYPE);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Could not read tool-call arguments from the CLI agent reply: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && node.isTextual() && !node.asText().trim().isEmpty()) {
                return node.asText().trim();
            }
        }
        return "";
    }
}
