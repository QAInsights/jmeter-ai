package org.qainsights.jmeter.ai.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral translation of a {@link ToolSpec}'s parameters into plain
 * JSON-Schema fragments. Both the Anthropic and OpenAI tool adapters build their
 * SDK-specific schema objects from these maps, so a tool is advertised
 * identically to every provider.
 */
public final class JsonSchemaMapper {

    private JsonSchemaMapper() {
    }

    /**
     * Builds the {@code properties} object of a tool's input schema, preserving
     * the spec's parameter order.
     */
    public static Map<String, Object> properties(ToolSpec spec) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (ToolParameter param : spec.getParameters()) {
            properties.put(param.getName(), propertySchema(param));
        }
        return properties;
    }

    /** Builds the JSON-Schema fragment describing a single parameter. */
    public static Map<String, Object> propertySchema(ToolParameter param) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", jsonType(param.getType()));
        if (param.getType() == ParamType.STRING_ARRAY) {
            schema.put("items", Collections.singletonMap("type", "string"));
        } else if (param.getType() == ParamType.OBJECT_ARRAY) {
            schema.put("items", Collections.singletonMap("type", "object"));
        }
        if (param.getDescription() != null && !param.getDescription().isEmpty()) {
            schema.put("description", param.getDescription());
        }
        if (!param.getEnumValues().isEmpty()) {
            schema.put("enum", param.getEnumValues());
        }
        return schema;
    }

    /** Names of the spec's required parameters, in declaration order. */
    public static List<String> required(ToolSpec spec) {
        List<String> required = new ArrayList<>();
        for (ToolParameter param : spec.getParameters()) {
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }
        return required;
    }

    /** Maps a neutral {@link ParamType} onto its JSON-Schema {@code type} keyword. */
    public static String jsonType(ParamType type) {
        switch (type) {
            case INTEGER:
                return "integer";
            case NUMBER:
                return "number";
            case BOOLEAN:
                return "boolean";
            case OBJECT:
                return "object";
            case STRING_ARRAY:
            case OBJECT_ARRAY:
                return "array";
            case STRING:
            default:
                return "string";
        }
    }
}
