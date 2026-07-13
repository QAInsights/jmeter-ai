package org.qainsights.jmeter.ai.agent.claude;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ClaudeToolAdapter}. */
class ClaudeToolAdapterTest {

    private final ClaudeToolAdapter adapter = new ClaudeToolAdapter();

    @Test
    void toAnthropicTool_mapsNameDescriptionSchemaAndRequired() {
        ToolSpec spec = ToolSpec.builder("update_element_property")
                .description("Sets a property")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("the id").required(true).build())
                .addParameter(ToolParameter.builder("method", ParamType.STRING)
                        .enumValues(Arrays.asList("GET", "POST")).build())
                .build();

        Tool tool = adapter.toAnthropicTool(spec);

        assertEquals("update_element_property", tool.name());
        assertEquals("Sets a property", tool.description().orElse(""));
        List<String> required = tool.inputSchema().required().orElse(Collections.emptyList());
        assertEquals(Collections.singletonList("element_id"), required);
        Map<String, JsonValue> props = tool.inputSchema().properties().get()._additionalProperties();
        assertTrue(props.containsKey("element_id"));
        assertTrue(props.containsKey("method"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toAnthropicTool_stringArrayParam_setsArrayTypeAndStringItems() {
        ToolSpec spec = ToolSpec.builder("set_property_list")
                .description("Replaces a list property")
                .addParameter(ToolParameter.builder("values", ParamType.STRING_ARRAY)
                        .description("the values").required(true).build())
                .build();

        Tool tool = adapter.toAnthropicTool(spec);

        Map<String, JsonValue> props = tool.inputSchema().properties().get()._additionalProperties();
        Map<String, Object> valuesSchema = props.get("values").convert(Map.class);
        assertEquals("array", valuesSchema.get("type"));
        Map<String, Object> items = (Map<String, Object>) valuesSchema.get("items");
        assertEquals("string", items.get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toAnthropicTool_objectArrayParam_setsArrayTypeAndObjectItems() {
        ToolSpec spec = ToolSpec.builder("set_structured_property_list")
                .description("Replaces a structured list property")
                .addParameter(ToolParameter.builder("entries", ParamType.OBJECT_ARRAY)
                        .description("the entries").required(true).build())
                .build();

        Tool tool = adapter.toAnthropicTool(spec);

        Map<String, JsonValue> props = tool.inputSchema().properties().get()._additionalProperties();
        Map<String, Object> entriesSchema = props.get("entries").convert(Map.class);
        assertEquals("array", entriesSchema.get("type"));
        Map<String, Object> items = (Map<String, Object>) entriesSchema.get("items");
        assertEquals("object", items.get("type"));
    }

    /** Builds a real ContentBlock from its JSON shape, bypassing strict builders. */
    private static ContentBlock block(Map<String, Object> json) {
        return JsonValue.from(json).convert(ContentBlock.class);
    }

    @Test
    void toAssistantTurn_extractsTextAndToolCalls() {
        Map<String, Object> textJson = new LinkedHashMap<>();
        textJson.put("type", "text");
        textJson.put("text", "Adding it now.");

        Map<String, Object> toolJson = new LinkedHashMap<>();
        toolJson.put("type", "tool_use");
        toolJson.put("id", "tu_42");
        toolJson.put("name", "add_element");
        toolJson.put("input", Collections.singletonMap("parent_id", "Test Plan"));

        List<ContentBlock> blocks = Arrays.asList(block(textJson), block(toolJson));

        AssistantTurn turn = adapter.toAssistantTurn(blocks);

        assertEquals("Adding it now.", turn.getText());
        assertTrue(turn.hasToolCalls());
        AssistantTurn.ToolCall call = turn.getToolCalls().get(0);
        assertEquals("tu_42", call.getId());
        assertEquals("add_element", call.getName());
        assertEquals("Test Plan", call.getArguments().get("parent_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toAssistantTurn_toolCallWithArrayArgument_arrivesAsListOfStrings() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("property", "Asserion.test_strings");
        input.put("values", Arrays.asList("200", "201"));

        Map<String, Object> toolJson = new LinkedHashMap<>();
        toolJson.put("type", "tool_use");
        toolJson.put("id", "tu_9");
        toolJson.put("name", "set_property_list");
        toolJson.put("input", input);

        AssistantTurn turn = adapter.toAssistantTurn(Collections.singletonList(block(toolJson)));

        List<Object> values = (List<Object>) turn.getToolCalls().get(0).getArguments().get("values");
        assertEquals(Arrays.asList("200", "201"), values);
    }

    @Test
    void toResultBlock_setsToolUseIdAndErrorFlag() {
        ToolResultBlockParam block = adapter.toResultBlock(
                new ToolOutcome("tu_7", "add_element", "ERROR [bad] nope", true));
        assertEquals("tu_7", block.toolUseId());
        assertTrue(block.isError().orElse(false));
    }
}
