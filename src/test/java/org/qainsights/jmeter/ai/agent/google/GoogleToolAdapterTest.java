package org.qainsights.jmeter.ai.agent.google;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link GoogleToolAdapter}. */
class GoogleToolAdapterTest {

    private final GoogleToolAdapter adapter = new GoogleToolAdapter();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Builds a real Part from its JSON shape, bypassing strict builders. */
    private static Part part(Map<String, Object> json) throws Exception {
        return Part.fromJson(JSON.writeValueAsString(json));
    }

    @Test
    void toFunctionDeclaration_mapsNameDescriptionSchemaAndRequired() {
        ToolSpec spec = ToolSpec.builder("update_element_property")
                .description("Sets a property")
                .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                        .description("the id").required(true).build())
                .addParameter(ToolParameter.builder("method", ParamType.STRING)
                        .enumValues(Arrays.asList("GET", "POST")).build())
                .build();

        FunctionDeclaration declaration = adapter.toFunctionDeclaration(spec);

        assertEquals("update_element_property", declaration.name().orElse(""));
        assertEquals("Sets a property", declaration.description().orElse(""));
        Schema parameters = declaration.parameters().orElseThrow();
        assertEquals("OBJECT", parameters.type().orElseThrow().toString());
        assertEquals(Collections.singletonList("element_id"), parameters.required().orElse(Collections.emptyList()));
        Map<String, Schema> properties = parameters.properties().orElse(Collections.emptyMap());
        assertTrue(properties.containsKey("element_id"));
        assertTrue(properties.containsKey("method"));
        assertEquals(Arrays.asList("GET", "POST"), properties.get("method").enum_().orElse(Collections.emptyList()));
    }

    @Test
    void toFunctionDeclaration_stringArrayParam_setsArrayTypeAndStringItems() {
        ToolSpec spec = ToolSpec.builder("set_property_list")
                .description("Replaces a list property")
                .addParameter(ToolParameter.builder("values", ParamType.STRING_ARRAY)
                        .description("the values").required(true).build())
                .build();

        FunctionDeclaration declaration = adapter.toFunctionDeclaration(spec);

        Schema values = declaration.parameters().orElseThrow().properties().orElseThrow().get("values");
        assertEquals("ARRAY", values.type().orElseThrow().toString());
        assertEquals("STRING", values.items().orElseThrow().type().orElseThrow().toString());
    }

    @Test
    void toFunctionDeclaration_objectArrayParam_setsArrayTypeAndObjectItems() {
        ToolSpec spec = ToolSpec.builder("set_structured_property_list")
                .description("Replaces a structured list property")
                .addParameter(ToolParameter.builder("entries", ParamType.OBJECT_ARRAY)
                        .description("the entries").required(true).build())
                .build();

        FunctionDeclaration declaration = adapter.toFunctionDeclaration(spec);

        Schema entries = declaration.parameters().orElseThrow().properties().orElseThrow().get("entries");
        assertEquals("ARRAY", entries.type().orElseThrow().toString());
        assertEquals("OBJECT", entries.items().orElseThrow().type().orElseThrow().toString());
    }

    @Test
    void toAssistantTurn_extractsTextAndToolCalls() throws Exception {
        Map<String, Object> textJson = new LinkedHashMap<>();
        textJson.put("text", "Adding it now.");

        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put("name", "add_element");
        functionCall.put("args", Collections.singletonMap("parent_id", "Test Plan"));
        Map<String, Object> toolJson = new LinkedHashMap<>();
        toolJson.put("functionCall", functionCall);

        List<Part> parts = Arrays.asList(part(textJson), part(toolJson));

        AssistantTurn turn = adapter.toAssistantTurn(parts);

        assertEquals("Adding it now.", turn.getText());
        assertTrue(turn.hasToolCalls());
        AssistantTurn.ToolCall call = turn.getToolCalls().get(0);
        assertEquals("call_0", call.getId());
        assertEquals("add_element", call.getName());
        assertEquals("Test Plan", call.getArguments().get("parent_id"));
    }

    @Test
    void toAssistantTurn_functionCallWithId_usesTheProvidedId() throws Exception {
        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put("id", "fc_42");
        functionCall.put("name", "get_tree_state");
        functionCall.put("args", Collections.emptyMap());
        Map<String, Object> toolJson = new LinkedHashMap<>();
        toolJson.put("functionCall", functionCall);

        AssistantTurn turn = adapter.toAssistantTurn(Collections.singletonList(part(toolJson)));

        assertEquals("fc_42", turn.getToolCalls().get(0).getId());
    }

    @Test
    void toAssistantTurn_mixedTextAndFunctionCallPart_keepsTheToolCall() throws Exception {
        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put("name", "get_tree_state");
        functionCall.put("args", Collections.emptyMap());
        Map<String, Object> mixedJson = new LinkedHashMap<>();
        mixedJson.put("text", "");
        mixedJson.put("functionCall", functionCall);

        AssistantTurn turn = adapter.toAssistantTurn(Collections.singletonList(part(mixedJson)));

        assertTrue(turn.hasToolCalls());
        assertEquals("get_tree_state", turn.getToolCalls().get(0).getName());
    }

    @Test
    void toAssistantTurn_skipsThoughtParts() throws Exception {
        Map<String, Object> thoughtJson = new LinkedHashMap<>();
        thoughtJson.put("text", "Let me think about this...");
        thoughtJson.put("thought", true);
        Map<String, Object> answerJson = new LinkedHashMap<>();
        answerJson.put("text", "Done.");

        AssistantTurn turn = adapter.toAssistantTurn(Arrays.asList(part(thoughtJson), part(answerJson)));

        assertEquals("Done.", turn.getText());
    }

    @Test
    void toFunctionResponsePart_successfulOutcome_setsOutputKey() {
        Part part = adapter.toFunctionResponsePart(
                new ToolOutcome("call_0", "add_element", "created http-sampler-1", false));

        assertEquals("add_element", part.functionResponse().orElseThrow().name().orElse(""));
        assertEquals("created http-sampler-1",
                part.functionResponse().orElseThrow().response().orElseThrow().get("output"));
    }

    @Test
    void toFunctionResponsePart_errorOutcome_setsErrorKey() {
        Part part = adapter.toFunctionResponsePart(
                new ToolOutcome("call_0", "delete_element", "ERROR [not_found] nope", true));

        assertEquals("ERROR [not_found] nope",
                part.functionResponse().orElseThrow().response().orElseThrow().get("error"));
    }

    @Test
    void toFunctionResponsePart_realCallId_isEchoedBack() throws Exception {
        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put("id", "fc_42");
        functionCall.put("name", "get_tree_state");
        functionCall.put("args", Collections.emptyMap());
        Map<String, Object> toolJson = new LinkedHashMap<>();
        toolJson.put("functionCall", functionCall);
        AssistantTurn.ToolCall call = adapter.toAssistantTurn(Collections.singletonList(part(toolJson)))
                .getToolCalls().get(0);

        Part response = adapter.toFunctionResponsePart(
                new ToolOutcome(call.getId(), call.getName(), "tree", false));

        assertEquals("fc_42", response.functionResponse().orElseThrow().id().orElse(null));
    }

    @Test
    void toFunctionResponsePart_syntheticCallId_leavesIdEmpty() throws Exception {
        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put("name", "get_tree_state");
        functionCall.put("args", Collections.emptyMap());
        Map<String, Object> toolJson = new LinkedHashMap<>();
        toolJson.put("functionCall", functionCall);
        AssistantTurn.ToolCall call = adapter.toAssistantTurn(Collections.singletonList(part(toolJson)))
                .getToolCalls().get(0);
        assertEquals("call_0", call.getId());

        Part response = adapter.toFunctionResponsePart(
                new ToolOutcome(call.getId(), call.getName(), "tree", false));

        assertTrue(response.functionResponse().orElseThrow().id().isEmpty());
    }
}
