package org.qainsights.jmeter.ai.agent.claude;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ClaudeChatModel} using a fake {@link ClaudeChatModel.MessageService}. */
class ClaudeChatModelTest {

    /** Builds a real assistant Message from its JSON shape, bypassing strict builders. */
    private static Message message(Object... content) {
        Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("id", "m_1");
        json.put("type", "message");
        json.put("role", "assistant");
        json.put("model", "claude");
        json.put("stop_reason", "end_turn");
        json.put("usage", mapOf("input_tokens", 0, "output_tokens", 0));
        json.put("content", java.util.Arrays.asList(content));
        return JsonValue.from(json).convert(Message.class);
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static Message textMessage(String text) {
        return message(mapOf("type", "text", "text", text));
    }

    private static Message toolMessage(String id, String name) {
        Map<String, Object> tu = new java.util.LinkedHashMap<>();
        tu.put("type", "tool_use");
        tu.put("id", id);
        tu.put("name", name);
        tu.put("input", Collections.emptyMap());
        return message(tu);
    }

    @Test
    void start_sendsUserMessageAndParsesToolCall() {
        Deque<Message> responses = new ArrayDeque<>();
        responses.add(toolMessage("tu_1", "get_tree_state"));
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return responses.removeFirst();
        };

        ClaudeChatModel model = new ClaudeChatModel(service, new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude", 1024);

        AssistantTurn turn = model.start("inspect the plan");

        assertTrue(turn.hasToolCalls());
        assertEquals("get_tree_state", turn.getToolCalls().get(0).getName());
        assertEquals(1, captured.get(0).messages().size());
    }

    @Test
    void next_appendsToolResultsAndGrowsHistory() {
        Deque<Message> responses = new ArrayDeque<>();
        responses.add(toolMessage("tu_1", "get_tree_state"));
        responses.add(textMessage("All set."));
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return responses.removeFirst();
        };

        ClaudeChatModel model = new ClaudeChatModel(service, new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude", 1024);

        model.start("inspect");
        AssistantTurn turn = model.next(Collections.singletonList(
                new ToolOutcome("tu_1", "get_tree_state", "tree", false)));

        assertFalse(turn.hasToolCalls());
        assertEquals("All set.", turn.getText());
        // History on the 2nd call: user + assistant(tool_use) + user(tool_result) = 3.
        assertTrue(captured.get(1).messages().size() > captured.get(0).messages().size());
        assertEquals(3, captured.get(1).messages().size());
    }

    @Test
    void constructor_withSeedHistory_prependsItToTheFirstRequest() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return textMessage("ok");
        };
        List<MessageParam> seed = Arrays.asList(
                MessageParam.builder().role(MessageParam.Role.USER).content("earlier question").build(),
                MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("earlier answer").build());

        ClaudeChatModel model = new ClaudeChatModel(service, new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude", 1024, seed);
        model.start("follow up");

        // seed (2) + the new user message (1) = 3.
        assertEquals(3, captured.get(0).messages().size());
    }

    @Test
    void constructor_withoutSeedHistory_sendsOnlyTheNewMessage() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return textMessage("ok");
        };

        ClaudeChatModel model = new ClaudeChatModel(service, new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude", 1024);
        model.start("hello");

        assertEquals(1, captured.get(0).messages().size());
    }

    @Test
    void updateToolSpecsChangesToolsAdvertisedOnNextRequest() {
        Deque<Message> responses = new ArrayDeque<>();
        responses.add(toolMessage("tu_1", "get_tree_state"));
        responses.add(textMessage("done"));
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return responses.removeFirst();
        };

        ClaudeChatModel model = new ClaudeChatModel(service, new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude", 1024);
        model.start("inspect");
        model.updateToolSpecs(List.of(ToolSpec.builder("run_test").description("Runs the plan").build()));
        model.next(Collections.singletonList(
                new ToolOutcome("tu_1", "get_tree_state", "tree", false)));

        assertTrue(captured.get(0).tools().isEmpty());
        assertTrue(captured.get(1).tools().isPresent());
        assertEquals("run_test", captured.get(1).tools().get().get(0).asTool().name());
    }

    @Test
    void cacheBreakpointsPreserveSystemToolsAndConversationPrefix() {
        var mapper = com.anthropic.core.ObjectMappers.jsonMapper();
        List<com.fasterxml.jackson.databind.JsonNode> requests = new ArrayList<>();
        var read = ToolSpec.builder("get_tree_state").description("Reads the plan").build();
        var inspect = ToolSpec.builder("inspect").description("Reads an element").build();
        var run = ToolSpec.builder("run_test").description("Runs the plan").build();
        var model = new ClaudeChatModel(params -> {
            requests.add(mapper.valueToTree(params._body()));
            return toolMessage("tu_" + requests.size(), "get_tree_state");
        }, new ClaudeToolAdapter(), List.of(read, inspect), "system", "claude", 1024);

        model.start("inspect");
        model.next(List.of(new ToolOutcome("tu_1", "get_tree_state", "tree", false)));
        model.updateToolSpecs(List.of(read, inspect, run));
        model.next(List.of(new ToolOutcome("tu_2", "get_tree_state", "updated tree", false)));

        var first = requests.get(0);
        var second = requests.get(1);
        var expanded = requests.get(2);
        assertEquals("ephemeral", first.at("/cache_control/type").asText());
        assertEquals("system", first.at("/system/0/text").asText());
        assertEquals("ephemeral", first.at("/system/0/cache_control/type").asText());
        assertTrue(first.at("/tools/0/cache_control").isMissingNode());
        assertEquals("ephemeral", first.at("/tools/1/cache_control/type").asText());
        assertEquals(first.path("tools").toString(), second.path("tools").toString());
        assertEquals(first.path("system").toString(), expanded.path("system").toString());
        assertEquals(first.at("/messages/0").toString(), second.at("/messages/0").toString());
        for (int i = 0; i < second.path("messages").size(); i++) {
            assertEquals(second.path("messages").get(i).toString(), expanded.path("messages").get(i).toString());
        }
        assertEquals(first.at("/tools/0").toString(), expanded.at("/tools/0").toString());
        assertEquals(first.at("/tools/1/input_schema").toString(), expanded.at("/tools/1/input_schema").toString());
        assertTrue(expanded.at("/tools/1/cache_control").isMissingNode());
        assertEquals("ephemeral", expanded.at("/tools/2/cache_control/type").asText());
    }

    @Test
    void compatibleEndpointKeepsUnannotatedRequest() {
        var mapper = com.anthropic.core.ObjectMappers.jsonMapper();
        List<com.fasterxml.jackson.databind.JsonNode> requests = new ArrayList<>();
        var model = new ClaudeChatModel(params -> {
            requests.add(mapper.valueToTree(params._body()));
            return textMessage("done");
        }, new ClaudeToolAdapter(), List.of(ToolSpec.builder("inspect").description("Reads the plan").build()),
                "system", "claude", 1024, List.of(), null, null, false);

        model.start("inspect");

        var request = requests.get(0);
        assertEquals("system", request.path("system").asText());
        assertTrue(request.path("cache_control").isMissingNode());
        assertTrue(request.at("/tools/0/cache_control").isMissingNode());
    }
}
