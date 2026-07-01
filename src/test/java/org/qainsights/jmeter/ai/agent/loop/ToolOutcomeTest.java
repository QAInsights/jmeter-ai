package org.qainsights.jmeter.ai.agent.loop;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToolOutcome}. */
class ToolOutcomeTest {

    private static AssistantTurn.ToolCall call() {
        return new AssistantTurn.ToolCall("tu_1", "get_tree_state", Collections.emptyMap());
    }

    @Test
    void from_success_carriesDataAndNotError() {
        ToolOutcome outcome = ToolOutcome.from(call(), ToolResult.ok("tree here"));
        assertEquals("tu_1", outcome.getToolCallId());
        assertEquals("get_tree_state", outcome.getName());
        assertEquals("tree here", outcome.getContent());
        assertFalse(outcome.isError());
    }

    @Test
    void from_error_flagsErrorAndIncludesCodeAndMessage() {
        ToolOutcome outcome = ToolOutcome.from(call(), ToolResult.error("element_not_found", "no such id"));
        assertTrue(outcome.isError());
        assertTrue(outcome.getContent().contains("element_not_found"));
        assertTrue(outcome.getContent().contains("no such id"));
    }

    @Test
    void nullContent_normalizedToEmpty() {
        assertEquals("", new ToolOutcome("id", "name", null, false).getContent());
    }
}
