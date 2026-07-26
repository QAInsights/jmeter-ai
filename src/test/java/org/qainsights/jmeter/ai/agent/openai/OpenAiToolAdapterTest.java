package org.qainsights.jmeter.ai.agent.openai;

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

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link OpenAiToolAdapter}. */
class OpenAiToolAdapterTest {

    private final OpenAiToolAdapter adapter = new OpenAiToolAdapter();

    /** Builds a real assistant message from its JSON shape, bypassing strict builders. */
    private static ChatCompletionMessage message(String content, Object... toolCalls) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("role", "assistant");
        json.put("content", content);
        if (toolCalls.length > 0) {
            json.put("tool_calls", Arrays.asList(toolCalls));
        }
        return JsonValue.from(json).convert(ChatCompletionMessage.class);
    }

    private static Map<String, Object> functionCall(String id, String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", arguments);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", id);
        call.put("type", "function");
        call.put("function", function);
        return call;
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOpenAiTool_mapsNameDescriptionSchemaAndRequired() {
        ToolSpec spec = ToolSpec.builder("update_element_property")
                .description("Sets a property")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("the id").required(true).build())
                .addParameter(ToolParameter.builder("method", ParamType.STRING)
                        .enumValues(Arrays.asList("GET", "POST")).build())
                .build();

        ChatCompletionFunctionTool tool = adapter.toOpenAiTool(spec);

        FunctionDefinition function = tool.function();
        assertEquals("update_element_property", function.name());
        assertEquals("Sets a property", function.description().orElse(""));

        Map<String, JsonValue> schema = function.parameters().get()._additionalProperties();
        assertEquals("object", schema.get("type").convert(String.class));
        assertEquals(Collections.singletonList("element_id"), schema.get("required").convert(List.class));
        Map<String, Object> properties = schema.get("properties").convert(Map.class);
        assertTrue(properties.containsKey("element_id"));
        assertTrue(properties.containsKey("method"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOpenAiTool_stringArrayParam_setsArrayTypeAndStringItems() {
        ToolSpec spec = ToolSpec.builder("set_property_list")
                .description("Replaces a list property")
                .addParameter(ToolParameter.builder("values", ParamType.STRING_ARRAY)
                        .description("the values").required(true).build())
                .build();

        Map<String, JsonValue> schema = adapter.toOpenAiTool(spec).function()
                .parameters().get()._additionalProperties();

        Map<String, Object> properties = schema.get("properties").convert(Map.class);
        Map<String, Object> values = (Map<String, Object>) properties.get("values");
        assertEquals("array", values.get("type"));
        assertEquals(Collections.singletonMap("type", "string"), values.get("items"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toOpenAiTool_noParameters_stillSendsAnObjectSchema() {
        Map<String, JsonValue> schema = adapter.toOpenAiTool(ToolSpec.builder("get_tree_state").build())
                .function().parameters().get()._additionalProperties();

        assertEquals("object", schema.get("type").convert(String.class));
        assertTrue(schema.get("properties").convert(Map.class).isEmpty());
        assertTrue(schema.get("required").convert(List.class).isEmpty());
    }

    @Test
    void toAssistantTurn_extractsTextAndToolCalls() {
        ChatCompletionMessage response = message("Adding it now.",
                functionCall("call_42", "add_element", "{\"parent_id\":\"Test Plan\"}"));

        AssistantTurn turn = adapter.toAssistantTurn(response);

        assertEquals("Adding it now.", turn.getText());
        assertTrue(turn.hasToolCalls());
        AssistantTurn.ToolCall call = turn.getToolCalls().get(0);
        assertEquals("call_42", call.getId());
        assertEquals("add_element", call.getName());
        assertEquals("Test Plan", call.getArguments().get("parent_id"));
    }

    @Test
    void toAssistantTurn_noContentAndNoToolCalls_yieldsEmptyTurn() {
        AssistantTurn turn = adapter.toAssistantTurn(message(null));

        assertEquals("", turn.getText());
        assertFalse(turn.hasToolCalls());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toAssistantTurn_toolCallWithArrayArgument_arrivesAsListOfStrings() {
        ChatCompletionMessage response = message(null, functionCall("call_9", "set_property_list",
                "{\"property\":\"Asserion.test_strings\",\"values\":[\"200\",\"201\"]}"));

        AssistantTurn turn = adapter.toAssistantTurn(response);

        List<Object> values = (List<Object>) turn.getToolCalls().get(0).getArguments().get("values");
        assertEquals(Arrays.asList("200", "201"), values);
    }

    @Test
    void toAssistantTurn_malformedArguments_yieldsEmptyArgumentsInsteadOfThrowing() {
        ChatCompletionMessage response = message(null,
                functionCall("call_7", "add_element", "{not json"));

        AssistantTurn turn = adapter.toAssistantTurn(response);

        assertTrue(turn.getToolCalls().get(0).getArguments().isEmpty());
    }

    @Test
    void toAssistantTurn_emptyArgumentsString_yieldsEmptyArguments() {
        ChatCompletionMessage response = message(null,
                functionCall("call_8", "get_tree_state", ""));

        assertTrue(turn(response).getToolCalls().get(0).getArguments().isEmpty());
    }

    private AssistantTurn turn(ChatCompletionMessage response) {
        return adapter.toAssistantTurn(response);
    }

    @Test
    void toAssistantTurn_multipleToolCalls_arePreservedInOrder() {
        ChatCompletionMessage response = message(null,
                functionCall("call_1", "get_tree_state", "{}"),
                functionCall("call_2", "get_element_config", "{\"element_id\":\"Test Plan\"}"));

        AssistantTurn turn = adapter.toAssistantTurn(response);

        assertEquals(2, turn.getToolCalls().size());
        assertEquals("get_tree_state", turn.getToolCalls().get(0).getName());
        assertEquals("get_element_config", turn.getToolCalls().get(1).getName());
    }

    @Test
    void toToolMessage_setsToolCallIdAndContent() {
        ChatCompletionToolMessageParam param = adapter.toToolMessage(
                new ToolOutcome("call_7", "add_element", "ERROR [bad] nope", true));

        assertEquals("call_7", param.toolCallId());
        assertEquals("ERROR [bad] nope", param.content().asText());
    }

    @Test
    void toToolMessage_successOutcome_carriesTheRawData() {
        ChatCompletionToolMessageParam param = adapter.toToolMessage(
                new ToolOutcome("call_1", "get_tree_state", "Test Plan", false));

        assertEquals("Test Plan", param.content().asText());
    }
}
