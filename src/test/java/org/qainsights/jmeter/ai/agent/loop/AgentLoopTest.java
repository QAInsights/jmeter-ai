package org.qainsights.jmeter.ai.agent.loop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AgentLoop} using a scripted {@link ChatModel} and a fake tool. */
class AgentLoopTest {

    private static final String RAW_JMX = "<jmeterTestPlan version=\"1.2\"></jmeterTestPlan>";

    /** Replays a queue of scripted assistant turns regardless of input. */
    private static final class ScriptedModel implements ChatModel {
        final Deque<AssistantTurn> turns = new ArrayDeque<>();
        final List<List<ToolOutcome>> received = new ArrayList<>();
        final List<String> followUps = new ArrayList<>();
        boolean followUpSupported = true;
        Runnable afterStart = () -> { };

        @Override
        public AssistantTurn start(String userMessage) {
            AssistantTurn turn = turns.removeFirst();
            afterStart.run();
            return turn;
        }

        @Override
        public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
            received.add(toolOutcomes);
            return turns.removeFirst();
        }

        @Override
        public AssistantTurn followUp(String userMessage) {
            followUps.add(userMessage);
            return followUpSupported ? turns.removeFirst() : null;
        }
    }

    private final List<Map<String, Object>> echoCalls = new ArrayList<>();

    private ToolExecutor executorWithEcho() {
        return executorWithEcho("echo");
    }

    private ToolExecutor executorWithEcho(String... names) {
        ToolRegistry registry = new ToolRegistry();
        for (String name : names) {
            registry.register(new Tool() {
                @Override
                public ToolSpec getSpec() {
                    return ToolSpec.builder(name).description("echo").build();
                }

                @Override
                public ToolResult execute(Map<String, Object> arguments) {
                    echoCalls.add(arguments);
                    return ToolResult.ok("echoed");
                }
            });
        }
        return new ToolExecutor(registry);
    }

    private static AssistantTurn toolTurn(String callId) {
        return toolTurn(callId, "echo");
    }

    private static AssistantTurn toolTurn(String callId, String toolName) {
        return new AssistantTurn("", Collections.singletonList(
                new AssistantTurn.ToolCall(callId, toolName, Collections.singletonMap("k", "v"))));
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
    void run_rawJmxFinalTurnFollowsUpAndExecutesImportTool() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));
        model.turns.add(toolTurn("import-1", "import_plan_content"));
        model.turns.add(new AssistantTurn("Plan imported.", Collections.emptyList()));
        List<String> progress = new ArrayList<>();

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho("import_plan_content"), 5)
                .run("build a plan", progress::add);

        assertTrue(result.isCompleted());
        assertEquals("Plan imported.", result.getFinalText());
        assertEquals(List.of(AgentLoop.RAW_JMX_NUDGE), model.followUps);
        assertEquals(1, echoCalls.size());
        assertTrue(progress.contains("\u21bb Reply contained raw JMX without changing the plan; asking the Agent to apply it with tools."));
    }

    @Test
    void run_rawJmxAfterNudgeDoesNotNudgeTwice() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));
        model.turns.add(new AssistantTurn("<jmeterTestPlan>second plan</jmeterTestPlan>", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5).run("build a plan", null);

        assertTrue(result.isCompleted());
        assertEquals("<jmeterTestPlan>second plan</jmeterTestPlan>", result.getFinalText());
        assertEquals(1, model.followUps.size());
    }

    @Test
    void run_successfulWriteToolPreventsRawJmxNudge() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("add-1", "add_element"));
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho("add_element"), 5)
                .run("add a timer", null);

        assertTrue(result.isCompleted());
        assertEquals(RAW_JMX, result.getFinalText());
        assertTrue(model.followUps.isEmpty());
    }

    @Test
    void run_successfulExpandToolsDoesNotPreventRawJmxNudge() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("expand-1", "expand_tools"));
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));
        model.turns.add(new AssistantTurn("Plan applied.", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho("expand_tools"), 5)
                .run("build a plan", null);

        assertTrue(result.isCompleted());
        assertEquals("Plan applied.", result.getFinalText());
        assertEquals(List.of(AgentLoop.RAW_JMX_NUDGE), model.followUps);
    }

    @Test
    void run_successfulReadToolDoesNotPreventRawJmxNudge() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("tree-1", ReadToolHandlers.GET_TREE_STATE));
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));
        model.turns.add(new AssistantTurn("Plan applied.", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model,
                executorWithEcho(ReadToolHandlers.GET_TREE_STATE), 5).run("build a plan", null);

        assertTrue(result.isCompleted());
        assertEquals("Plan applied.", result.getFinalText());
        assertEquals(List.of(AgentLoop.RAW_JMX_NUDGE), model.followUps);
    }

    @Test
    void run_failedWriteToolDoesNotPreventRawJmxNudge() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("add-1", "add_element"));
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));
        model.turns.add(new AssistantTurn("Plan applied.", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5)
                .run("build a plan", null);

        assertTrue(result.isCompleted());
        assertEquals("Plan applied.", result.getFinalText());
        assertEquals(List.of(AgentLoop.RAW_JMX_NUDGE), model.followUps);
        assertTrue(model.received.get(0).get(0).isError());
    }

    @Test
    void run_unsupportedFollowUpCompletesWithOriginalJmxText() {
        ScriptedModel model = new ScriptedModel();
        model.followUpSupported = false;
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5).run("build a plan", null);

        assertTrue(result.isCompleted());
        assertEquals(RAW_JMX, result.getFinalText());
        assertEquals(List.of(AgentLoop.RAW_JMX_NUDGE), model.followUps);
    }

    @Test
    void run_iterationCapPreventsRawJmxFollowUp() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 1).run("build a plan", null);

        assertTrue(result.isCompleted());
        assertEquals(RAW_JMX, result.getFinalText());
        assertTrue(model.followUps.isEmpty());
    }

    @Test
    void run_plainTextDoesNotTriggerRawJmxFollowUp() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn("Here is how to build a test plan.", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5).run("explain", null);

        assertTrue(result.isCompleted());
        assertEquals("Here is how to build a test plan.", result.getFinalText());
        assertTrue(model.followUps.isEmpty());
    }

    @Test
    void run_openJmxTagWithoutClosingTagDoesNotTriggerFollowUp() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn("<jmeterTestPlan>", Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5).run("explain", null);

        assertTrue(result.isCompleted());
        assertEquals("<jmeterTestPlan>", result.getFinalText());
        assertTrue(model.followUps.isEmpty());
    }

    @Test
    void run_declinedImportDoesNotTriggerFollowUp() {
        boolean[] executed = {false};
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolSpec getSpec() {
                return ToolSpec.builder("import_plan_content").description("Imports a plan").build();
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                executed[0] = true;
                return ToolResult.ok("imported");
            }
        });
        ToolExecutor executor = new ToolExecutor(registry, Set.of("import_plan_content"),
                (toolName, arguments) -> false);
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("import-1", "import_plan_content"));
        model.turns.add(new AssistantTurn(RAW_JMX, Collections.emptyList()));

        AgentLoop.AgentResult result = new AgentLoop(model, executor, 5).run("build a plan", null);

        assertTrue(result.isCompleted());
        assertEquals(RAW_JMX, result.getFinalText());
        assertFalse(executed[0]);
        assertTrue(model.received.get(0).get(0).isError());
        assertTrue(model.received.get(0).get(0).getContent().contains(ToolExecutor.ERR_DECLINED));
        assertTrue(model.followUps.isEmpty());
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
    void run_reusesOnlyTheLatestFullTreeForLargeExactRepeats() {
        String treeTool = ReadToolHandlers.GET_TREE_STATE;
        String first = "a".repeat(2048);
        String changed = "b" + first.substring(1);
        String small = first.substring(1);
        String unchanged = "Tree snapshot unchanged; identical to the latest full get_tree_state result above.";
        Deque<ToolResult> toolResults = new ArrayDeque<>(List.of(
                ToolResult.ok(first), ToolResult.ok(first), ToolResult.ok(changed),
                ToolResult.error("unavailable", "Tree unavailable"), ToolResult.ok(changed),
                ToolResult.ok(small), ToolResult.ok(small), ToolResult.ok(changed)));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolSpec getSpec() {
                return ToolSpec.builder(treeTool).description("tree").build();
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return toolResults.removeFirst();
            }
        });

        ScriptedModel model = new ScriptedModel();
        List<String> expected = List.of(first, unchanged, changed, "ERROR [unavailable] Tree unavailable",
                unchanged, small, small, changed);
        for (int i = 0; i < expected.size(); i++) {
            model.turns.add(new AssistantTurn("", List.of(
                    new AssistantTurn.ToolCall("tree-" + i, treeTool, Map.of()))));
        }
        model.turns.add(new AssistantTurn("Done.", List.of()));

        var result = new AgentLoop(model, new ToolExecutor(registry), 10).run("inspect", null);

        assertTrue(result.isCompleted());
        assertTrue(toolResults.isEmpty());
        assertEquals(expected.size(), model.received.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(1, model.received.get(i).size());
            ToolOutcome outcome = model.received.get(i).get(0);
            assertEquals("tree-" + i, outcome.getToolCallId());
            assertEquals(treeTool, outcome.getName());
            assertEquals(expected.get(i), outcome.getContent());
            assertEquals(i == 3, outcome.isError());
        }
    }

    @Test
    void run_keepsSameTurnTreeResultsInCallOrder() {
        String treeTool = ReadToolHandlers.GET_TREE_STATE;
        String snapshot = "tree".repeat(1024);
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolSpec getSpec() {
                return ToolSpec.builder(treeTool).description("tree").build();
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                executions.incrementAndGet();
                return ToolResult.ok(snapshot);
            }
        });
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn("", List.of(
                new AssistantTurn.ToolCall("tree-z", treeTool, Map.of()),
                new AssistantTurn.ToolCall("tree-a", treeTool, Map.of()))));
        model.turns.add(new AssistantTurn("Done.", List.of()));

        var result = new AgentLoop(model, new ToolExecutor(registry), 3).run("inspect", null);

        assertTrue(result.isCompleted());
        assertEquals(2, executions.get());
        assertEquals(1, model.received.size());
        List<ToolOutcome> batch = model.received.get(0);
        assertEquals(List.of("tree-z", "tree-a"), batch.stream().map(ToolOutcome::getToolCallId).toList());
        assertEquals(List.of(snapshot, "Tree snapshot unchanged; identical to the latest full get_tree_state result above."),
                batch.stream().map(ToolOutcome::getContent).toList());
        assertTrue(batch.stream().noneMatch(ToolOutcome::isError));
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

    @Test
    void run_interrupted_stopsBeforeRunningMoreTools() {
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("c1"));
        model.turns.add(toolTurn("c2"));

        Thread.currentThread().interrupt();
        AgentLoop.AgentResult result;
        try {
            result = new AgentLoop(model, executorWithEcho(), 5).run("go", null);
        } finally {
            Thread.interrupted();
        }

        assertFalse(result.isCompleted());
        assertTrue(echoCalls.isEmpty());
        assertTrue(model.received.isEmpty());
    }

    @Test
    void run_stopRequestedByFirstToolStopsBeforeSecondToolAndModelNext() {
        AtomicBoolean stopRequested = new AtomicBoolean();
        AtomicInteger toolExecutions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolSpec getSpec() {
                return ToolSpec.builder("flip").description("flip").build();
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                toolExecutions.incrementAndGet();
                stopRequested.set(true);
                return ToolResult.ok("done");
            }
        });
        ScriptedModel model = new ScriptedModel();
        model.turns.add(new AssistantTurn("Working", Arrays.asList(
                new AssistantTurn.ToolCall("tc_1", "flip", Collections.emptyMap()),
                new AssistantTurn.ToolCall("tc_2", "flip", Collections.emptyMap()))));

        AgentLoop.AgentResult result = new AgentLoop(model, new ToolExecutor(registry), 5, stopRequested::get)
                .run("go", null);

        assertFalse(result.isCompleted());
        assertEquals("Working", result.getFinalText());
        assertEquals(1, toolExecutions.get());
        assertTrue(model.received.isEmpty());
    }

    @Test
    void run_stopRequestedAfterStartStopsBeforeRunningTools() {
        AtomicBoolean stopRequested = new AtomicBoolean();
        ScriptedModel model = new ScriptedModel();
        model.turns.add(toolTurn("tc_1"));
        model.afterStart = () -> stopRequested.set(true);

        AgentLoop.AgentResult result = new AgentLoop(model, executorWithEcho(), 5, stopRequested::get)
                .run("go", null);

        assertFalse(result.isCompleted());
        assertTrue(echoCalls.isEmpty());
        assertTrue(model.received.isEmpty());
    }
}
