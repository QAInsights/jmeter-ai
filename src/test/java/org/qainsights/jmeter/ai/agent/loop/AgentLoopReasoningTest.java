package org.qainsights.jmeter.ai.agent.loop;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the reasoning forwarding in {@link AgentLoop}: each turn's
 * thinking text is drained from the model and passed to the run's reasoning
 * consumer, blank reasoning is skipped, and a null consumer is safe.
 */
class AgentLoopReasoningTest {

    /** Scripted model that also replays a queue of reasoning strings. */
    private static final class ScriptedModel implements ChatModel {
        final Deque<AssistantTurn> turns = new ArrayDeque<>();
        final Deque<String> reasonings = new ArrayDeque<>();

        @Override
        public AssistantTurn start(String userMessage) {
            return turns.removeFirst();
        }

        @Override
        public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
            return turns.removeFirst();
        }

        @Override
        public String consumeLastReasoning() {
            return reasonings.isEmpty() ? null : reasonings.removeFirst();
        }
    }

    private static AssistantTurn textTurn(String text) {
        return new AssistantTurn(text, Collections.emptyList());
    }

    private static AssistantTurn toolTurn(String callId) {
        return new AssistantTurn("", Collections.singletonList(
                new AssistantTurn.ToolCall(callId, "echo", Collections.singletonMap("k", "v"))));
    }

    private static ToolExecutor executorWithEcho() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolSpec getSpec() {
                return ToolSpec.builder("echo").description("echo").build();
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.ok("echoed");
            }
        });
        return new ToolExecutor(registry);
    }

    @Test
    void reasoningFromEveryTurnIsForwarded() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("tu_1"));
        model.reasonings.add("thinking about tools…");
        model.turns.add(textTurn("done"));
        model.reasonings.add("final thoughts");

        List<String> received = new ArrayList<>();
        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5)
                .run("hi", null, null, received::add);

        assertTrue(result.isCompleted());
        assertEquals(List.of("thinking about tools…", "final thoughts"), received);
    }

    @Test
    void blankReasoningIsSkipped() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(textTurn("done"));
        model.reasonings.add("   ");

        List<String> received = new ArrayList<>();
        new AgentLoop(model, executorWithEcho(), 5).run("hi", null, null, received::add);

        assertTrue(received.isEmpty());
    }

    @Test
    void nullReasoningConsumerIsSafe() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(textTurn("done"));
        model.reasonings.add("thoughts");

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5)
                .run("hi", null, null, null);

        assertTrue(result.isCompleted());
    }

    @Test
    void defaultChatModelHasNoReasoning() {
        ChatModel model = new ChatModel() {
            @Override
            public AssistantTurn start(String userMessage) {
                return textTurn("x");
            }

            @Override
            public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
                return textTurn("y");
            }
        };
        assertNull(model.consumeLastReasoning());
    }
}
