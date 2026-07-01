package org.qainsights.jmeter.ai.agent.claude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

/**
 * Translates between our provider-neutral tool model and the Anthropic
 * (anthropic-java) message API: {@link ToolSpec} &rarr; {@link Tool} with a JSON
 * input schema, an assistant response's content blocks &rarr; {@link AssistantTurn},
 * and a {@link ToolOutcome} &rarr; {@link ToolResultBlockParam}.
 */
public final class ClaudeToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeToolAdapter.class);

    /** Converts a provider-neutral spec into an Anthropic tool definition. */
    public Tool toAnthropicTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        List<String> required = new ArrayList<>();

        for (ToolParameter param : spec.getParameters()) {
            Map<String, Object> propSchema = new LinkedHashMap<>();
            propSchema.put("type", jsonType(param.getType()));
            if (param.getDescription() != null && !param.getDescription().isEmpty()) {
                propSchema.put("description", param.getDescription());
            }
            if (!param.getEnumValues().isEmpty()) {
                propSchema.put("enum", param.getEnumValues());
            }
            properties.putAdditionalProperty(param.getName(), JsonValue.from(propSchema));
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(properties.build())
                .required(required)
                .build();

        return Tool.builder()
                .name(spec.getName())
                .description(spec.getDescription())
                .inputSchema(schema)
                .build();
    }

    /** Flattens an assistant response's content blocks into a neutral turn. */
    public AssistantTurn toAssistantTurn(List<ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        List<AssistantTurn.ToolCall> calls = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block.text().isPresent()) {
                text.append(block.text().get().text());
            } else if (block.toolUse().isPresent()) {
                ToolUseBlock tu = block.toolUse().get();
                calls.add(new AssistantTurn.ToolCall(tu.id(), tu.name(), toArguments(tu._input())));
            }
        }
        return new AssistantTurn(text.toString(), calls);
    }

    /** Builds a tool_result block to send the outcome of a tool call back to the model. */
    public ToolResultBlockParam toResultBlock(ToolOutcome outcome) {
        return ToolResultBlockParam.builder()
                .toolUseId(outcome.getToolCallId())
                .content(outcome.getContent())
                .isError(outcome.isError())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toArguments(JsonValue input) {
        try {
            Map<String, Object> args = input.convert(Map.class);
            return args == null ? new LinkedHashMap<>() : args;
        } catch (RuntimeException e) {
            log.warn("Could not parse tool_use input as a map: {}", input, e);
            return new LinkedHashMap<>();
        }
    }

    private static String jsonType(ParamType type) {
        switch (type) {
            case INTEGER:
                return "integer";
            case NUMBER:
                return "number";
            case BOOLEAN:
                return "boolean";
            case OBJECT:
                return "object";
            case STRING:
            default:
                return "string";
        }
    }
}
