package org.qainsights.jmeter.ai.agent.claude;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
}
