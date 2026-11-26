package org.qainsights.jmeter.ai.agent.openai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link OpenAiResponsesModel}. */
class OpenAiResponsesModelTest {

    private static Response response(List<Object> outputItems) {
        return response(outputItems, "completed");
    }

    private static Response response(List<Object> outputItems, String status) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "resp_1");
        json.put("object", "response");
        json.put("model", "gpt-6.1-terra");
        json.put("status", status);
        if ("incomplete".equals(status)) {
            json.put("incomplete_details", Map.of("reason", "max_output_tokens"));
        }
        json.put("output", outputItems);
        json.put("usage", Map.of(
                "input_tokens", 10,
                "input_tokens_details", Map.of("cached_tokens", 2, "cache_write_tokens", 3),
                "output_tokens", 7,
                "output_tokens_details", Map.of("reasoning_tokens", 4),
                "total_tokens", 17));
        return JsonValue.from(json).convert(Response.class);
    }

    private static Map<String, Object> messageItem(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "output_text");
        content.put("text", text);
        content.put("annotations", List.of());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "msg_1");
        item.put("type", "message");
        item.put("role", "assistant");
        item.put("status", "completed");
        item.put("content", List.of(content));
        return item;
    }

    private static Map<String, Object> reasoningItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "rs_1");
        item.put("type", "reasoning");
        item.put("summary", List.of());
        item.put("encrypted_content", "encrypted");
        return item;
    }

    private static Map<String, Object> functionCallItem(String callId, String name, String arguments) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "fc_1");
        item.put("type", "function_call");
        item.put("call_id", callId);
        item.put("name", name);
        item.put("arguments", arguments);
        item.put("status", "completed");
        return item;
    }

    private static OpenAiResponsesModel model(OpenAiResponsesModel.ResponseService service) {
        return new OpenAiResponsesModel(service, new OpenAiToolAdapter(), List.of(
                ToolSpec.builder("get_tree_state").description("Reads the tree").build()),
                "system", "gpt-6.1-terra", 1024, Collections.emptyList(),
                new ReasoningSettings(true, "high"));
    }

    private static JsonNode body(ResponseCreateParams params) {
        return ObjectMappers.jsonMapper().valueToTree(params._body());
    }

    @Test
    void applies_onlyForNonNoneReasoningEffort() {
        assertTrue(OpenAiResponsesModel.applies("gpt-6.1-terra", new ReasoningSettings(true, "high")));
        assertTrue(OpenAiResponsesModel.applies("gpt-5.6-terra", new ReasoningSettings(true, "high")));
        assertFalse(OpenAiResponsesModel.applies("gpt-6.1-terra", new ReasoningSettings(false, "high")));
        assertFalse(OpenAiResponsesModel.applies("gpt-4o", new ReasoningSettings(true, "high")));
    }

    @Test
    void openAiFactory_selectsResponsesTransportForTerraHigh() {
        var factory = JMeterAgent.openAiFactory(params -> {
            throw new AssertionError("Chat Completions must not be used for Terra high");
        }, params -> response(List.of(messageItem("done"))), "gpt-6.1-terra", 1024,
                new ReasoningSettings(true, "high"));

        assertInstanceOf(OpenAiResponsesModel.class,
                factory.create(Collections.emptyList(), "system", Collections.emptyList()));
    }

    @Test
    void start_usesResponsesApiReasoningShapeAndTools() {
        List<ResponseCreateParams> captured = new ArrayList<>();
        OpenAiResponsesModel.ResponseService service = params -> {
            captured.add(params);
            return response(List.of(messageItem("done")));
        };

        AssistantTurn turn = model(service).start("inspect the plan");

        assertEquals("done", turn.getText());
        JsonNode body = body(captured.get(0));
        assertEquals("high", body.at("/reasoning/effort").asText());
        assertEquals("gpt-6.1-terra", body.at("/model").asText());
        assertEquals(1024, body.at("/max_output_tokens").asInt());
        assertEquals("reasoning.encrypted_content", body.at("/include/0").asText());
        assertEquals("function", body.at("/tools/0/type").asText());
        assertEquals("get_tree_state", body.at("/tools/0/name").asText());
        assertFalse(body.has("reasoning_effort"), "Responses API must not use the Chat Completions field");
        assertFalse(body.has("temperature"));
        assertEquals("inspect the plan", body.at("/input/0/content").asText());
    }

    @Test
    void followUp_appendsUserMessageAfterFinalAssistantTurn() {
        Deque<Response> responses = new ArrayDeque<>();
        responses.add(response(List.of(messageItem("First final answer."))));
        responses.add(response(List.of(messageItem("Follow-up answer."))));
        List<ResponseCreateParams> captured = new ArrayList<>();
        OpenAiResponsesModel.ResponseService service = params -> {
            captured.add(params);
            return responses.removeFirst();
        };

        OpenAiResponsesModel chat = model(service);
        AssistantTurn first = chat.start("initial request");
        AssistantTurn second = chat.followUp("nudge");

        assertEquals("First final answer.", first.getText());
        assertEquals("Follow-up answer.", second.getText());
        JsonNode input = body(captured.get(1)).path("input");
        assertEquals(3, input.size());
        assertEquals("user", input.get(0).path("role").asText());
        assertEquals("initial request", input.get(0).path("content").asText());
        assertEquals("assistant", input.get(1).path("role").asText());
        assertTrue(input.get(1).toString().contains("First final answer."));
        assertEquals("user", input.get(2).path("role").asText());
        assertEquals("nudge", input.get(2).path("content").asText());
    }

    @Test
    void next_replaysFunctionCallAndAddsFunctionCallOutput() {
        Deque<Response> responses = new ArrayDeque<>();
        responses.add(response(List.of(reasoningItem(),
                functionCallItem("call_1", "get_tree_state", "{\"detail\":\"full\"}"))));
        responses.add(response(List.of(messageItem("All set."))));
        List<ResponseCreateParams> captured = new ArrayList<>();
        OpenAiResponsesModel.ResponseService service = params -> {
            captured.add(params);
            return responses.removeFirst();
        };

        OpenAiResponsesModel chat = model(service);
        AssistantTurn first = chat.start("inspect");
        AssistantTurn second = chat.next(List.of(new ToolOutcome("call_1", "get_tree_state", "tree", false)));

        assertTrue(first.hasToolCalls());
        assertEquals("call_1", first.getToolCalls().get(0).getId());
        assertEquals("full", first.getToolCalls().get(0).getArguments().get("detail"));
        assertEquals("All set.", second.getText());

        JsonNode input = body(captured.get(1)).path("input");
        assertEquals("user", input.get(0).path("role").asText());
        assertEquals("reasoning", input.get(1).path("type").asText());
        assertEquals("encrypted", input.get(1).path("encrypted_content").asText());
        assertEquals("function_call", input.get(2).path("type").asText());
        assertEquals("call_1", input.get(2).path("call_id").asText());
        assertEquals("function_call_output", input.get(3).path("type").asText());
        assertEquals("call_1", input.get(3).path("call_id").asText());
        assertEquals("tree", input.get(3).path("output").asText());
    }

    @Test
    void incompleteResponseIsRejectedInsteadOfCompletingTheRun() {
        OpenAiResponsesModel.ResponseService service = params -> response(List.of(reasoningItem()), "incomplete");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> model(service).start("inspect"));

        assertTrue(error.getMessage().contains("incomplete"), error.getMessage());
        assertTrue(error.getMessage().contains("max_output_tokens"), error.getMessage());
    }

    @Test
    void usage_trackerReadsResponsesApiUsageShape() {
        OpenAiResponsesModel.ResponseService service = params -> response(List.of(messageItem("done")));
        OpenAiResponsesModel chat = model(service);

        chat.start("inspect");

        assertTrue(chat.usageTracker().summary().contains("5"), chat.usageTracker().summary());
        assertTrue(chat.usageTracker().summary().contains("2"), chat.usageTracker().summary());
        assertTrue(chat.usageTracker().summary().contains("3"), chat.usageTracker().summary());
        assertTrue(chat.usageTracker().summary().contains("7"), chat.usageTracker().summary());
        assertTrue(chat.usageTracker().summary().contains("4"), chat.usageTracker().summary());
    }
}
