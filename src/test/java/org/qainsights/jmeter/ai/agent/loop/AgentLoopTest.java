package org.qainsights.jmeter.ai.agent.loop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AgentLoop} using a scripted {@link ChatModel} and a fake tool. */
class AgentLoopTest {

    /** Replays a queue of scripted assistant turns regardless of input. */
    private static final class ScriptedModel implements ChatModel {
        final Deque<AssistantTurn> turns = new ArrayDeque<>();
        final List<List<ToolOutcome>> received = new ArrayList<>();

        @Override
        public AssistantTurn start(String userMessage) {
            return turns.removeFirst();
        }

        @Override
        public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
            received.add(toolOutcomes);
            return turns.removeFirst();
        }
    }

    private final List<Map<String, Object>> echoCalls = new ArrayList<>();

    private ToolExecutor executorWithEcho() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolSpec getSpec() {
                return ToolSpec.builder("echo").description("echo").build();
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                echoCalls.add(arguments);
                return ToolResult.ok("echoed");
            }
        });
        return new ToolExecutor(registry);
    }

    private static AssistantTurn toolTurn(String callId) {
        return new AssistantTurn("", Collections.singletonList(
                new AssistantTurn.ToolCall(callId, "echo", Collections.singletonMap("k", "v"))));
    }

    @Test
    void run_noToolCalls_completesImmediately() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn("Hello, nothing to do.", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5).run("hi", null);

        assertTrue(result.isCompleted());
        assertEquals("Hello, nothing to do.", result.getFinalText());
        assertEquals(1, result.getIterations());
        assertTrue(echoCalls.isEmpty());
    }

    @Test
    void run_executesToolThenCompletes() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("tu_1"));
        model.turns.add(new AssistantTurn("Done.", Collections.emptyList()));

        List<String> progress = new ArrayList<>();
        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5).run("go", progress::add);

        assertTrue(result.isCompleted());
        assertEquals("Done.", result.getFinalText());
        assertEquals(2, result.getIterations());
        assertEquals(1, echoCalls.size());
        assertEquals("v", echoCalls.get(0).get("k"));
        // The outcome of the tool call was fed back to the model.
        assertEquals(1, model.received.size());
        assertEquals("echoed", model.received.get(0).get(0).getContent());
        assertFalse(progress.isEmpty());
    }

    @Test
    void run_withOnToolCallStartedListener_isNotifiedForEachToolCallBeforeExecution() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("tu_1"));
        model.turns.add(new AssistantTurn("Done.", Collections.emptyList()));

        List<AssistantTurn.ToolCall> started = new ArrayList<>();
        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5)
                .run("go", null, started::add);

        assertTrue(result.isCompleted());
        assertEquals(1, started.size());
        assertEquals("echo", started.get(0).getName());
        assertEquals("v", started.get(0).getArguments().get("k"));
    }

    @Test
    void run_withNullOnToolCallStartedListener_behavesLikeTheTwoArgOverload() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("tu_1"));
        model.turns.add(new AssistantTurn("Done.", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5).run("go", null, null);

        assertTrue(result.isCompleted());
        assertEquals(1, echoCalls.size());
    }

    @Test
    void run_hitsIterationCap_returnsExhausted() {
        ScriptedModel model = new ScriptedModel();
        // Always asks for another tool call.
        model.turns.addAll(Arrays.asList(
                toolTurn("a"), toolTurn("b"), toolTurn("c"), toolTurn("d")));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 3).run("loop", null);

        assertFalse(result.isCompleted());
        assertEquals(3, result.getIterations());
        // Executed tools on iterations 1 and 2; iteration 3 stopped before executing again.
        assertEquals(2, echoCalls.size());
    }
}
