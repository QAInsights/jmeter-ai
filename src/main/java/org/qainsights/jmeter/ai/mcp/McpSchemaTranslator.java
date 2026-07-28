package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * Translates an MCP tool's JSON Schema into the plugin's provider-neutral {@link ToolSpec}.
 * <p>
 * <strong>This conversion is lossy, by necessity.</strong> {@link ToolParameter} models a
 * flat parameter with one of seven {@link ParamType}s, whereas MCP servers may publish
 * arbitrary JSON Schema: nested objects, typed array items, {@code oneOf}, numeric enums.
 * Everything below the top level is therefore collapsed to {@code OBJECT} or
 * {@code OBJECT_ARRAY}.
 * <p>
 * Collapsing alone would be actively harmful - a model told only that
 * {@code browser_fill_form.fields} is "an array of objects" will invent the object shape
 * and the call will fail. So whenever detail is dropped, the discarded sub-schema is
 * appended to the parameter's <em>description</em>, which is free-form text passed to the
 * provider verbatim. The structure survives as prose even though it cannot survive as
 * structure.
 */
public final class McpSchemaTranslator {

    /** Guards against a pathological schema producing a giant prompt. */
    private static final int MAX_INLINE_SCHEMA_CHARS = 1_500;

    private McpSchemaTranslator() {
    }

    /**
     * Converts an MCP tool definition into a {@link ToolSpec}.
     *
     * @param tool the tool as advertised by the server
     * @return an equivalent spec, with nested schema detail preserved in descriptions
     */
    public static ToolSpec toToolSpec(McpTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        ToolSpec.Builder builder = ToolSpec.builder(tool.name()).description(tool.description());

        JsonNode schema = tool.inputSchema();
        JsonNode properties = schema.path("properties");
        Set<String> required = requiredNames(schema);

        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            builder.addParameter(toParameter(field.getKey(), field.getValue(),
                    required.contains(field.getKey())));
        }
        return builder.build();
    }

    private static Set<String> requiredNames(JsonNode schema) {
        Set<String> names = new HashSet<>();
        for (JsonNode name : schema.path("required")) {
            names.add(name.asText());
        }
        return names;
    }

    private static ToolParameter toParameter(String name, JsonNode schema, boolean required) {
        ParamType type = paramType(schema);
        return ToolParameter.builder(name, type)
                .required(required)
                .description(describe(schema, type))
                .enumValues(stringEnumValues(schema))
                .build();
    }

    /**
     * Maps a JSON Schema {@code type} onto a {@link ParamType}.
     * <p>
     * Unions such as {@code ["string","null"]} take the first non-null entry, since the
     * optionality is already carried by the required flag. An absent or unrecognised type
     * falls back to {@code STRING}: the model then sends a string, which a server will
     * reject with a readable message the agent can self-correct from - strictly better
     * than us guessing a structured type.
     */
    static ParamType paramType(JsonNode schema) {
        String type = declaredType(schema);
        switch (type) {
            case "integer":
                return ParamType.INTEGER;
            case "number":
                return ParamType.NUMBER;
            case "boolean":
                return ParamType.BOOLEAN;
            case "object":
                return ParamType.OBJECT;
            case "array":
                return "object".equals(declaredType(schema.path("items")))
                        ? ParamType.OBJECT_ARRAY
                        : ParamType.STRING_ARRAY;
            case "string":
            default:
                return ParamType.STRING;
        }
    }

    private static String declaredType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (!"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
            return "";
        }
        return type.asText("");
    }

    /**
     * Only string enums survive as a {@link ToolParameter} enum, because
     * {@code ToolParameter.enumValues} is a list of strings. Non-string enums are handled
     * by {@link #describe} instead, so the constraint is still communicated.
     */
    private static List<String> stringEnumValues(JsonNode schema) {
        List<String> values = new ArrayList<>();
        JsonNode enumNode = schema.path("enum");
        if (enumNode.isArray() && "string".equals(declaredType(schema))) {
            for (JsonNode value : enumNode) {
                if (value.isTextual()) {
                    values.add(value.asText());
                }
            }
        }
        return values;
    }

    /**
     * Builds the parameter description, appending any schema detail that the flat
     * {@link ParamType} model cannot represent.
     */
    private static String describe(JsonNode schema, ParamType type) {
        StringBuilder description = new StringBuilder(schema.path("description").asText(""));

        String detail = droppedDetail(schema, type);
        if (!detail.isEmpty()) {
            if (description.length() > 0) {
                description.append(' ');
            }
            description.append(detail);
        }

        JsonNode defaultValue = schema.get("default");
        if (defaultValue != null && !defaultValue.isNull()) {
            if (description.length() > 0) {
                description.append(' ');
            }
            description.append("Defaults to ").append(defaultValue).append('.');
        }
        return description.toString();
    }

    private static String droppedDetail(JsonNode schema, ParamType type) {
        if (type == ParamType.OBJECT) {
            return inlineSchema("Object schema: ", schema);
        }
        if (type == ParamType.OBJECT_ARRAY) {
            return inlineSchema("Each array item has this schema: ", schema.path("items"));
        }
        JsonNode enumNode = schema.path("enum");
        if (enumNode.isArray() && !enumNode.isEmpty() && !"string".equals(declaredType(schema))) {
            return "Allowed values: " + enumNode + ".";
        }
        return "";
    }

    private static String inlineSchema(String prefix, JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull() || schema.isEmpty()) {
            return "";
        }
        String json = schema.toString();
        if (json.length() > MAX_INLINE_SCHEMA_CHARS) {
            json = json.substring(0, MAX_INLINE_SCHEMA_CHARS) + "...(truncated)";
        }
        return prefix + json + ".";
    }
}
