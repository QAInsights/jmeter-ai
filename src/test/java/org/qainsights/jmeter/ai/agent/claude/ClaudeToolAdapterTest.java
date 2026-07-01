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
    void toResultBlock_setsToolUseIdAndErrorFlag() {
        ToolResultBlockParam block = adapter.toResultBlock(
                new ToolOutcome("tu_7", "add_element", "ERROR [bad] nope", true));
        assertEquals("tu_7", block.toolUseId());
        assertTrue(block.isError().orElse(false));
    }
}
