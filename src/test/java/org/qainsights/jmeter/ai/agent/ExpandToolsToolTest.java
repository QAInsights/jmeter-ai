package org.qainsights.jmeter.ai.agent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpandToolsToolTest {

    @Test
    void specAdvertisesEscapeHatchWithRequiredReason() {
        ExpandToolsTool tool = new ExpandToolsTool(reason -> ToolResult.ok("expanded"));

        assertEquals(ExpandToolsTool.EXPAND_TOOLS, tool.getSpec().getName());
        assertTrue(tool.getSpec().getDescription().contains("Do NOT invent"));
        assertEquals(1, tool.getSpec().getRequiredParameters().size());
        assertEquals("reason", tool.getSpec().getRequiredParameters().get(0).getName());
    }

    @Test
    void executeDelegatesTrimmedReasonToHandler() {
        AtomicReference<String> seen = new AtomicReference<>();
        ExpandToolsTool tool = new ExpandToolsTool(reason -> {
            seen.set(reason);
            return ToolResult.ok("done");
        });

        ToolResult result = tool.execute(Map.of("reason", "  run the test  "));

        assertTrue(result.isSuccess());
        assertEquals("run the test", seen.get());
    }

    @Test
    void longReasonIsTruncatedBeforeReachingHandler() {
        AtomicReference<String> seen = new AtomicReference<>();
        ExpandToolsTool tool = new ExpandToolsTool(reason -> {
            seen.set(reason);
            return ToolResult.ok("done");
        });

        tool.execute(Map.of("reason", "x".repeat(600)));

        assertEquals(ExpandToolsTool.MAX_REASON_CHARS, seen.get().length());
    }

    @Test
    void missingOrNullReasonYieldsEmptyStringNotError() {
        AtomicReference<String> seen = new AtomicReference<>();
        ExpandToolsTool tool = new ExpandToolsTool(reason -> {
            seen.set(reason);
            return ToolResult.ok("done");
        });

        ToolResult result = tool.execute(Map.of());

        assertTrue(result.isSuccess());
        assertEquals("", seen.get());
    }

    @Test
    void constructorRejectsNullHandler() {
        assertThrows(IllegalArgumentException.class, () -> new ExpandToolsTool(null));
    }
}
