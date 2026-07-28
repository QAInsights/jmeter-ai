package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpSchemaTranslator}, including the compromises the flat
 * {@link ToolParameter} model forces on nested JSON Schema.
 */
class McpSchemaTranslatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpTool tool(String schemaJson) {
        try {
            JsonNode schema = MAPPER.readTree(schemaJson);
            return new McpTool("browser_test", "A test tool", schema);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad test schema", e);
        }
    }

    private static ToolParameter param(ToolSpec spec, String name) {
        return spec.getParameters().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No parameter named " + name));
    }

    @Test
    void should_carryNameAndDescription() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\"}"));
        assertEquals("browser_test", spec.getName());
        assertEquals("A test tool", spec.getDescription());
        assertTrue(spec.getParameters().isEmpty());
    }

    @Test
    void should_mapScalarTypes() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"s\":{\"type\":\"string\"},"
                + "\"i\":{\"type\":\"integer\"},"
                + "\"n\":{\"type\":\"number\"},"
                + "\"b\":{\"type\":\"boolean\"},"
                + "\"o\":{\"type\":\"object\"}}}"));

        assertEquals(ParamType.STRING, param(spec, "s").getType());
        assertEquals(ParamType.INTEGER, param(spec, "i").getType());
        assertEquals(ParamType.NUMBER, param(spec, "n").getType());
        assertEquals(ParamType.BOOLEAN, param(spec, "b").getType());
        assertEquals(ParamType.OBJECT, param(spec, "o").getType());
    }

    @Test
    void should_distinguishArrayItemTypes() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                + "\"fields\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}}"));

        assertEquals(ParamType.STRING_ARRAY, param(spec, "paths").getType());
        assertEquals(ParamType.OBJECT_ARRAY, param(spec, "fields").getType());
    }

    @Test
    void should_markRequiredParameters() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\"},\"timeout\":{\"type\":\"integer\"}},"
                + "\"required\":[\"url\"]}"));

        assertTrue(param(spec, "url").isRequired());
        assertFalse(param(spec, "timeout").isRequired());
        assertEquals(1, spec.getRequiredParameters().size());
    }

    @Test
    void should_preserveStringEnum() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"button\":{\"type\":\"string\",\"enum\":[\"left\",\"right\",\"middle\"]}}}"));

        assertEquals(List.of("left", "right", "middle"), param(spec, "button").getEnumValues());
    }

    @Test
    void should_resolveNullableUnionToItsConcreteType() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"index\":{\"type\":[\"integer\",\"null\"]}}}"));

        assertEquals(ParamType.INTEGER, param(spec, "index").getType(),
                "optionality is carried by the required flag, not the type");
    }

    @Test
    void should_fallBackToString_when_typeIsAbsentOrUnknown() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"description\":\"no type\"},"
                + "\"b\":{\"type\":\"weird\"}}}"));

        assertEquals(ParamType.STRING, param(spec, "a").getType());
        assertEquals(ParamType.STRING, param(spec, "b").getType());
    }

    // The lossy edges ---------------------------------------------------------------

    @Test
    void should_inlineItemSchema_when_arrayOfObjectsLosesItsShape() {
        // Modelled on browser_fill_form, whose fields[] item shape cannot be represented
        // by OBJECT_ARRAY. Without this the model would invent the field object.
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"fields\":{\"type\":\"array\",\"description\":\"Fields to fill\","
                + "\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"name\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}}}}}}"));

        String description = param(spec, "fields").getDescription();
        assertTrue(description.startsWith("Fields to fill"), "original text must be kept");
        assertTrue(description.contains("Each array item has this schema"));
        assertTrue(description.contains("\"value\""), "the dropped item shape must survive as prose");
    }

    @Test
    void should_inlineObjectSchema_when_nestedPropertiesAreLost() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"viewport\":{\"type\":\"object\",\"properties\":{"
                + "\"width\":{\"type\":\"integer\"},\"height\":{\"type\":\"integer\"}}}}}"));

        String description = param(spec, "viewport").getDescription();
        assertTrue(description.contains("Object schema"));
        assertTrue(description.contains("width"));
    }

    @Test
    void should_describeNonStringEnum_when_itCannotBeRepresented() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"level\":{\"type\":\"integer\",\"enum\":[1,2,3]}}}"));

        ToolParameter level = param(spec, "level");
        assertTrue(level.getEnumValues().isEmpty(), "ToolParameter enums are strings only");
        assertTrue(level.getDescription().contains("Allowed values"),
                "the constraint must still reach the model");
        assertTrue(level.getDescription().contains("1"));
    }

    @Test
    void should_mentionDefaultValue() {
        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool("{\"type\":\"object\",\"properties\":{"
                + "\"slowly\":{\"type\":\"boolean\",\"default\":false}}}"));

        assertTrue(param(spec, "slowly").getDescription().contains("Defaults to false"));
    }

    @Test
    void should_truncateOversizedInlineSchema() {
        StringBuilder huge = new StringBuilder("{\"type\":\"object\",\"properties\":{\"big\":"
                + "{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < 400; i++) {
            huge.append("\"field").append(i).append("\":{\"type\":\"string\"},");
        }
        huge.setLength(huge.length() - 1);
        huge.append("}}}}");

        ToolSpec spec = McpSchemaTranslator.toToolSpec(tool(huge.toString()));

        String description = param(spec, "big").getDescription();
        assertTrue(description.contains("(truncated)"), "a runaway schema must not blow up the prompt");
        assertTrue(description.length() < 2_000);
    }

    @Test
    void should_tolerateMissingSchema() {
        McpTool bare = new McpTool("browser_snapshot", "Take a snapshot", null);
        ToolSpec spec = McpSchemaTranslator.toToolSpec(bare);
        assertTrue(spec.getParameters().isEmpty());
    }

    @Test
    void should_rejectNullTool() {
        assertThrows(IllegalArgumentException.class, () -> McpSchemaTranslator.toToolSpec(null));
    }
}
