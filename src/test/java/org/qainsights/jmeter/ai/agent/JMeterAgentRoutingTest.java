package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.agent.tool.handlers.ApplyCorrelationHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.RunTestHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.StopTestHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JMeterAgentRoutingTest {

    @BeforeEach
    void resetUndoNudge() {
        JMeterAgent.resetUndoNudgeForTests();
    }

    @Test
    void focusedRouteFiltersSpecsAndPublishesNoticeBeforeModelStarts() {
        List<String> events = new ArrayList<>();
        AtomicReference<List<ToolSpec>> capturedSpecs = new AtomicReference<>();
        AgentChatModelFactory factory = (specs, prompt, prior) -> {
            events.add("factory");
            capturedSpecs.set(specs);
            return textModel(events);
        };
        AtomicReference<String> routedMessage = new AtomicReference<>();
        AgentRequestRouter router = message -> {
            events.add("route");
            routedMessage.set(message);
            return AgentRequestRouter.Decision.focused(
                    AgentRequestRouter.Route.CORRELATE, Map.of("CORRELATE", 0.94), 0.9);
        };
        AtomicReference<AgentRequestRouter.Notice> notice = new AtomicReference<>();
        JMeterAgent agent = new JMeterAgent(factory, 5, null, router);

        agent.run("resolved prompt", "find tokens", List.of(), null, null, null, value -> {
            events.add("notice");
            notice.set(value);
        });

        List<String> names = capturedSpecs.get().stream().map(ToolSpec::getName).toList();
        assertTrue(names.contains(ApplyCorrelationHandler.APPLY_CORRELATION));
        assertFalse(names.contains(DeleteElementHandler.DELETE_ELEMENT));
        assertEquals(names, notice.get().toolNames());
        assertTrue(notice.get().totalToolCount() > names.size());
        assertEquals("find tokens", routedMessage.get());
        assertEquals(List.of("route", "notice", "factory", "start"), events);
    }

    @Test
    void disabledRouterPreservesFullRegistryAndPublishesNoNotice() {
        AtomicReference<List<ToolSpec>> capturedSpecs = new AtomicReference<>();
        AtomicReference<AgentRequestRouter.Notice> notice = new AtomicReference<>();
        AgentChatModelFactory factory = (specs, prompt, prior) -> {
            capturedSpecs.set(specs);
            return textModel(new ArrayList<>());
        };
        JMeterAgent agent = new JMeterAgent(factory, 5, null, null);

        agent.run("inspect", "inspect", List.of(), null, null, null, notice::set);

        assertTrue(capturedSpecs.get().size() >= 21);
        assertNull(notice.get());
    }

    @Test
    void routerFailurePublishesUnavailableNoticeAndUsesFullRegistry() {
        AtomicReference<List<ToolSpec>> capturedSpecs = new AtomicReference<>();
        AtomicReference<AgentRequestRouter.Notice> notice = new AtomicReference<>();
        AgentChatModelFactory factory = (specs, prompt, prior) -> {
            capturedSpecs.set(specs);
            return textModel(new ArrayList<>());
        };
        JMeterAgent agent = new JMeterAgent(factory, 5, null, message -> {
            throw new IllegalStateException("offline");
        });

        agent.run("inspect", "inspect", List.of(), null, null, null, notice::set);

        assertTrue(capturedSpecs.get().size() >= 21);
        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, notice.get().decision().outcome());
        assertEquals(capturedSpecs.get().size(), notice.get().totalToolCount());
    }

    @Test
    void focusedRunWithExpansionAdvertisesExpandToolsButExcludesItFromCardCount() {
        AtomicReference<List<ToolSpec>> capturedSpecs = new AtomicReference<>();
        List<AgentRequestRouter.Notice> notices = new ArrayList<>();
        AgentChatModelFactory factory = (specs, prompt, prior) -> {
            capturedSpecs.set(specs);
            return textModel(new ArrayList<>());
        };
        JMeterAgent agent = new JMeterAgent(factory, 5, null,
                message -> AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.INSPECT_EXPLAIN, Map.of(), 0.9));

        withExpansionFlag(() -> agent.run("explain", "explain", List.of(), null, null, null,
                notices::add));

        List<String> names = capturedSpecs.get().stream().map(ToolSpec::getName).toList();
        assertTrue(names.contains(ExpandToolsTool.EXPAND_TOOLS));
        assertFalse(names.contains(RunTestHandler.RUN_TEST));
        assertFalse(notices.get(0).toolNames().contains(ExpandToolsTool.EXPAND_TOOLS));
    }

    @Test
    void expansionFlagOffKeepsExpandToolsOutOfFocusedPacks() {
        AtomicReference<List<ToolSpec>> capturedSpecs = new AtomicReference<>();
        AgentChatModelFactory factory = (specs, prompt, prior) -> {
            capturedSpecs.set(specs);
            return textModel(new ArrayList<>());
        };
        JMeterAgent agent = new JMeterAgent(factory, 5, null,
                message -> AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.INSPECT_EXPLAIN, Map.of(), 0.9));

        agent.run("explain", "explain", List.of(), null, null, null, null);

        List<String> names = capturedSpecs.get().stream().map(ToolSpec::getName).toList();
        assertFalse(names.contains(ExpandToolsTool.EXPAND_TOOLS));
    }

    @Test
    void modelCanEscapeFocusedPackViaExpandTools() {
        List<List<ToolOutcome>> received = new ArrayList<>();
        List<AgentRequestRouter.Notice> notices = new ArrayList<>();
        AtomicInteger routeCalls = new AtomicInteger();
        AgentRequestRouter router = message -> {
            int call = routeCalls.incrementAndGet();
            return call == 1
                    ? AgentRequestRouter.Decision.focused(
                            AgentRequestRouter.Route.INSPECT_EXPLAIN, Map.of(), 0.9)
                    : AgentRequestRouter.Decision.focused(
                            AgentRequestRouter.Route.RUN_DIAGNOSE, Map.of(), 0.88);
        };
        AgentChatModelFactory factory = (specs, prompt, prior) -> scriptedModel(received,
                new AssistantTurn("", List.of(
                        new AssistantTurn.ToolCall("1", ExpandToolsTool.EXPAND_TOOLS,
                                Map.of("reason", "run the test plan")))),
                new AssistantTurn("", List.of(
                        new AssistantTurn.ToolCall("2", StopTestHandler.STOP_TEST, Map.of()))),
                new AssistantTurn("done", List.of()));
        JMeterAgent agent = new JMeterAgent(factory, 5, null, router);

        withExpansionFlag(() -> agent.run("run it", "run it", List.of(), null, null, null,
                notices::add));

        assertEquals(2, routeCalls.get());
        assertEquals(2, notices.size());
        assertTrue(notices.get(1).isExpansion());
        assertEquals(List.of(RunTestHandler.RUN_TEST, StopTestHandler.STOP_TEST,
                "get_test_results"), notices.get(1).addedToolNames());
        assertTrue(received.get(0).get(0).getContent().contains("Additional tools"));
        String stopOutcome = received.get(1).get(0).getContent();
        assertFalse(stopOutcome.contains("unknown_tool"));
    }

    @Test
    void unknownToolGuessCanBeRecoveredThroughExpandTools() {
        List<List<ToolOutcome>> received = new ArrayList<>();
        AtomicInteger routeCalls = new AtomicInteger();
        AgentRequestRouter router = message -> routeCalls.incrementAndGet() == 1
                ? AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.INSPECT_EXPLAIN, Map.of(), 0.9)
                : AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.RUN_DIAGNOSE, Map.of(), 0.88);
        AgentChatModelFactory factory = (specs, prompt, prior) -> scriptedModel(received,
                new AssistantTurn("", List.of(
                        new AssistantTurn.ToolCall("1", RunTestHandler.RUN_TEST, Map.of()))),
                new AssistantTurn("", List.of(
                        new AssistantTurn.ToolCall("2", ExpandToolsTool.EXPAND_TOOLS,
                                Map.of("reason", "run")))),
                new AssistantTurn("", List.of(
                        new AssistantTurn.ToolCall("3", RunTestHandler.RUN_TEST, Map.of()))),
                new AssistantTurn("done", List.of()));
        JMeterAgent agent = new JMeterAgent(factory, 5, null, router);

        withExpansionFlag(() -> agent.run("run it", "run it", List.of(), null, null, null, null));

        assertTrue(received.get(0).get(0).getContent().contains("unknown_tool"));
        assertTrue(received.get(1).get(0).getContent().contains("Additional tools"));
        assertFalse(received.get(2).get(0).getContent().contains("unknown_tool"));
    }

    private static void withExpansionFlag(Runnable action) {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        JMeterUtils.setProperty(AgentRoutingConfig.EXPANSION_ENABLED_KEY, "true");
        try {
            action.run();
        } finally {
            JMeterUtils.getJMeterProperties().remove(AgentRoutingConfig.EXPANSION_ENABLED_KEY);
        }
    }

    private static ChatModel scriptedModel(List<List<ToolOutcome>> received, AssistantTurn... turns) {
        return new ChatModel() {
            private int index;

            @Override
            public AssistantTurn start(String userMessage) {
                return turns[index++];
            }

            @Override
            public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
                received.add(toolOutcomes);
                return turns[Math.min(index++, turns.length - 1)];
            }
        };
    }

    private static ChatModel textModel(List<String> events) {
        return new ChatModel() {
            @Override
            public AssistantTurn start(String userMessage) {
                events.add("start");
                return new AssistantTurn("done", List.of());
            }

            @Override
            public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
                return new AssistantTurn("done", List.of());
            }
        };
    }
}
