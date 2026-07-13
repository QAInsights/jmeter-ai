package org.qainsights.jmeter.ai.agent.loop;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AssistantTurn} and its nested {@link AssistantTurn.ToolCall}. */
class AssistantTurnTest {

    @Test
    void textOnlyTurn_hasNoToolCalls() {
        AssistantTurn turn = new AssistantTurn("hi", null);
        assertEquals("hi", turn.getText());
        assertFalse(turn.hasToolCalls());
        assertTrue(turn.getToolCalls().isEmpty());
    }

    @Test
    void nullText_normalizedToEmpty() {
        assertEquals("", new AssistantTurn(null, null).getText());
    }

    @Test
    void toolCall_exposesIdNameAndImmutableArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("parent_id", "Test Plan");
        AssistantTurn.ToolCall call = new AssistantTurn.ToolCall("tu_1", "add_element", args);
        AssistantTurn turn = new AssistantTurn("", Collections.singletonList(call));

        assertTrue(turn.hasToolCalls());
        assertEquals("tu_1", call.getId());
        assertEquals("add_element", call.getName());
        assertEquals("Test Plan", call.getArguments().get("parent_id"));
        assertThrows(UnsupportedOperationException.class, () -> call.getArguments().put("x", "y"));
    }

    @Test
    void nullArgs_normalizedToEmptyMap() {
        AssistantTurn.ToolCall call = new AssistantTurn.ToolCall("id", "name", null);
        assertTrue(call.getArguments().isEmpty());
    }
}
