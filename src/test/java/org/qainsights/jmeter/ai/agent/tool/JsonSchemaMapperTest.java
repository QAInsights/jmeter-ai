package org.qainsights.jmeter.ai.agent.tool;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link JsonSchemaMapper}, shared by the Claude and OpenAI tool adapters. */
class JsonSchemaMapperTest {

    private static ToolSpec spec() {
        return ToolSpec.builder("update_element_property")
                .description("Sets a property")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("the id").required(true).build())
                .addParameter(ToolParameter.builder("method", ParamType.STRING)
                        .enumValues(Arrays.asList("GET", "POST")).build())
                .addParameter(ToolParameter.builder("force", ParamType.BOOLEAN).build())
                .build();
    }

    @Test
    void properties_preservesDeclarationOrder() {
        List<String> keys = new java.util.ArrayList<>(JsonSchemaMapper.properties(spec()).keySet());
        assertEquals(Arrays.asList("element_id", "method", "force"), keys);
    }

    @Test
    void required_listsOnlyRequiredParameters() {
        assertEquals(Collections.singletonList("element_id"), JsonSchemaMapper.required(spec()));
    }

    @Test
    void propertySchema_includesDescriptionAndEnum() {
        Map<String, Object> properties = JsonSchemaMapper.properties(spec());

        Map<?, ?> elementId = (Map<?, ?>) properties.get("element_id");
        assertEquals("string", elementId.get("type"));
        assertEquals("the id", elementId.get("description"));
        assertFalse(elementId.containsKey("enum"));

        Map<?, ?> method = (Map<?, ?>) properties.get("method");
        assertEquals(Arrays.asList("GET", "POST"), method.get("enum"));
        assertFalse(method.containsKey("description"));
    }

    @Test
    void propertySchema_stringArray_hasStringItems() {
        ToolSpec arraySpec = ToolSpec.builder("set_property_list")
                .addParameter(ToolParameter.builder("values", ParamType.STRING_ARRAY).required(true).build())
                .build();

        Map<?, ?> values = (Map<?, ?>) JsonSchemaMapper.properties(arraySpec).get("values");

        assertEquals("array", values.get("type"));
        assertEquals(Collections.singletonMap("type", "string"), values.get("items"));
    }

    @Test
    void propertySchema_objectArray_hasObjectItems() {
        ToolSpec arraySpec = ToolSpec.builder("set_structured_property_list")
                .addParameter(ToolParameter.builder("entries", ParamType.OBJECT_ARRAY).required(true).build())
                .build();

        Map<?, ?> entries = (Map<?, ?>) JsonSchemaMapper.properties(arraySpec).get("entries");

        assertEquals("array", entries.get("type"));
        assertEquals(Collections.singletonMap("type", "object"), entries.get("items"));
    }

    @Test
    void jsonType_mapsEveryParamType() {
        assertEquals("string", JsonSchemaMapper.jsonType(ParamType.STRING));
        assertEquals("integer", JsonSchemaMapper.jsonType(ParamType.INTEGER));
        assertEquals("number", JsonSchemaMapper.jsonType(ParamType.NUMBER));
        assertEquals("boolean", JsonSchemaMapper.jsonType(ParamType.BOOLEAN));
        assertEquals("object", JsonSchemaMapper.jsonType(ParamType.OBJECT));
        assertEquals("array", JsonSchemaMapper.jsonType(ParamType.STRING_ARRAY));
        assertEquals("array", JsonSchemaMapper.jsonType(ParamType.OBJECT_ARRAY));
    }

    @Test
    void properties_specWithNoParameters_isEmpty() {
        assertTrue(JsonSchemaMapper.properties(ToolSpec.builder("get_tree_state").build()).isEmpty());
        assertTrue(JsonSchemaMapper.required(ToolSpec.builder("get_tree_state").build()).isEmpty());
    }
}
