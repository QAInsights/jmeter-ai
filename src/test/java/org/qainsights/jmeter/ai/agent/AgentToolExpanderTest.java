package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.RunTestHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.StopTestHandler;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class AgentToolExpanderTest {

    @Test
    void focusedReRouteRegistersMissingPackToolsAndPublishesExpansionNotice() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.INSPECT_EXPLAIN);
        AtomicReference<AgentRequestRouter.Notice> notice = new AtomicReference<>();
        AgentToolExpander expander = expander(
                message -> AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.RUN_DIAGNOSE, Map.of(), 0.9),
                "run my test", full, live, notice::set, 1);
        live.register(expander.tool());

        ToolResult result = expander.expand("run the plan");

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains(RunTestHandler.RUN_TEST));
        assertTrue(result.getData().contains(StopTestHandler.STOP_TEST));
        assertTrue(live.isRegistered(RunTestHandler.RUN_TEST));
        assertTrue(notice.get().isExpansion());
        assertEquals(List.of(RunTestHandler.RUN_TEST, StopTestHandler.STOP_TEST,
                "get_test_results"), notice.get().addedToolNames());
        assertFalse(notice.get().toolNames().contains(ExpandToolsTool.EXPAND_TOOLS));
        assertEquals(full.size(), notice.get().totalToolCount());
    }

    @Test
    void unavailableAndAllToolsDecisionsRegisterEveryRemainingTool() {
        for (AgentRequestRouter.Decision decision : List.of(
                AgentRequestRouter.Decision.unavailable(),
                AgentRequestRouter.Decision.allTools(
                        AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR, Map.of(), 0.4))) {
            ToolRegistry full = AgentToolRegistry.createDefault();
            ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.PLAN_FILES);
            AgentToolExpander expander = expander(
                    message -> decision, "help", full, live, null, 1);
            live.register(expander.tool());

            ToolResult result = expander.expand("need more");

            assertTrue(result.isSuccess());
            assertTrue(live.isRegistered(RunTestHandler.RUN_TEST));
            assertTrue(live.isRegistered(DeleteElementHandler.DELETE_ELEMENT));
            assertEquals(full.size() + 1, live.size());
        }
    }

    @Test
    void reRouteToSameFamilyAddsNothingAndSkipsNotice() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.INSPECT_EXPLAIN);
        AtomicReference<AgentRequestRouter.Notice> notice = new AtomicReference<>();
        AgentToolExpander expander = expander(
                message -> AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.INSPECT_EXPLAIN, Map.of(), 0.9),
                "explain", full, live, notice::set, 1);
        live.register(expander.tool());
        int before = live.size();

        ToolResult result = expander.expand("explain more");

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("already cover"));
        assertEquals(before, live.size());
        assertNull(notice.get());
    }

    @Test
    void expansionCapPreventsFurtherJevCalls() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.INSPECT_EXPLAIN);
        AtomicInteger routeCalls = new AtomicInteger();
        AgentToolExpander expander = expander(
                message -> {
                    routeCalls.incrementAndGet();
                    return AgentRequestRouter.Decision.focused(
                            AgentRequestRouter.Route.RUN_DIAGNOSE, Map.of(), 0.9);
                },
                "run", full, live, null, 1);
        live.register(expander.tool());

        expander.expand("run");
        ToolResult second = expander.expand("correlate");

        assertEquals(1, routeCalls.get());
        assertTrue(second.getData().contains("cannot be expanded further"));
    }

    @Test
    void alreadyFullRegistryShortCircuitsWithoutRouting() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        AtomicInteger routeCalls = new AtomicInteger();
        AgentToolExpander expander = expander(
                message -> {
                    routeCalls.incrementAndGet();
                    return AgentRequestRouter.Decision.unavailable();
                },
                "x", full, full, null, 1);

        ToolResult result = expander.expand("more");

        assertEquals(0, routeCalls.get());
        assertTrue(result.getData().contains("already available"));
    }

    @Test
    void routerFailureExpandsToAllTools() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.CORRELATE);
        AgentToolExpander expander = expander(
                message -> {
                    throw new IllegalStateException("offline");
                },
                "x", full, live, null, 1);
        live.register(expander.tool());

        ToolResult result = expander.expand("need");

        assertTrue(result.isSuccess());
        assertEquals(full.size() + 1, live.size());
    }

    @Test
    void reRouteMessageCarriesOriginalRequestAndReason() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.INSPECT_EXPLAIN);
        AtomicReference<String> routed = new AtomicReference<>();
        AgentToolExpander expander = expander(
                message -> {
                    routed.set(message);
                    return AgentRequestRouter.Decision.unavailable();
                },
                "add a sampler", full, live, null, 1);

        expander.expand("run the test plan");

        assertTrue(routed.get().contains("add a sampler"));
        assertTrue(routed.get().contains("run the test plan"));
    }

    @Test
    void newlyExposedDestructiveToolsRemainConfirmationGated() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.INSPECT_EXPLAIN);
        AgentToolExpander expander = expander(
                message -> AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.EDIT_TEST_PLAN, Map.of(), 0.95),
                "delete", full, live, null, 1);
        ToolExecutor executor = new ToolExecutor(live, Set.of(DeleteElementHandler.DELETE_ELEMENT),
                (name, args) -> false);

        expander.expand("delete an element");
        ToolResult declined = executor.execute(DeleteElementHandler.DELETE_ELEMENT,
                Map.of("element_id", "e1"));

        assertEquals(ToolExecutor.ERR_DECLINED, declined.getErrorCode());
    }

    @Test
    void registerIfEnabledOnlyAppliesToFocusedRunsWithFlagOn() {
        try (MockedStatic<AiConfig> config = defaults()) {
            ToolRegistry full = AgentToolRegistry.createDefault();
            ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.INSPECT_EXPLAIN);
            AgentRequestRouter.Decision focused = AgentRequestRouter.Decision.focused(
                    AgentRequestRouter.Route.INSPECT_EXPLAIN, Map.of(), 0.9);
            AgentRequestRouter router = message -> focused;

            AgentToolExpander.registerIfEnabled(router, "m", full, live, focused, null);
            assertFalse(live.isRegistered(ExpandToolsTool.EXPAND_TOOLS));

            config.when(() -> AiConfig.getProperty(
                    AgentRoutingConfig.EXPANSION_ENABLED_KEY, "false")).thenReturn("true");
            AgentToolExpander.registerIfEnabled(router, "m", full, live,
                    AgentRequestRouter.Decision.unavailable(), null);
            assertFalse(live.isRegistered(ExpandToolsTool.EXPAND_TOOLS));
            AgentToolExpander.registerIfEnabled(router, "m", full, live, focused, null);
            assertTrue(live.isRegistered(ExpandToolsTool.EXPAND_TOOLS));
        }
    }

    @Test
    void registerIfEnabledSkipsWhenSelectFellBackToFullRegistry() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(
                    AgentRoutingConfig.EXPANSION_ENABLED_KEY, "false")).thenReturn("true");
            ToolRegistry full = AgentToolRegistry.createDefault();
            AgentRequestRouter.Decision focused = AgentRequestRouter.Decision.focused(
                    AgentRequestRouter.Route.INSPECT_EXPLAIN, Map.of(), 0.9);

            AgentToolExpander.registerIfEnabled(message -> focused, "m", full, full,
                    focused, null);

            assertFalse(full.isRegistered(ExpandToolsTool.EXPAND_TOOLS));
        }
    }

    @Test
    void expandPublishesLiveSpecsToBoundSink() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = focusedRegistry(full, AgentRequestRouter.Route.INSPECT_EXPLAIN);
        AgentToolExpander expander = expander(
                message -> AgentRequestRouter.Decision.unavailable(), "m", full, live, null, 1);
        live.register(expander.tool());
        AtomicReference<List<ToolSpec>> pushed = new AtomicReference<>();
        expander.bindSpecSink(pushed::set);

        expander.expand("need everything");

        assertEquals(live.getSpecs().stream().map(ToolSpec::getName).toList(),
                pushed.get().stream().map(ToolSpec::getName).toList());
        assertTrue(pushed.get().stream().anyMatch(s -> RunTestHandler.RUN_TEST.equals(s.getName())));
    }

    @Test
    void expandToolParityDoesNotShortCircuitMissingStandardTool() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        ToolRegistry live = new ToolRegistry();
        List<Tool> all = new ArrayList<>(full.getAll());
        Tool missing = all.get(all.size() - 1);
        for (Tool tool : all) {
            if (tool != missing) {
                live.register(tool);
            }
        }
        AgentToolExpander expander = expander(
                message -> AgentRequestRouter.Decision.unavailable(), "m", full, live, null, 1);
        live.register(expander.tool());
        assertEquals(full.size(), live.size());

        ToolResult result = expander.expand("need the last tool");

        assertTrue(result.isSuccess());
        assertTrue(live.isRegistered(missing.getSpec().getName()));
    }

    private static AgentToolExpander expander(AgentRequestRouter router, String message,
                                              ToolRegistry full, ToolRegistry live,
                                              java.util.function.Consumer<AgentRequestRouter.Notice> notice,
                                              int max) {
        return new AgentToolExpander(
                new AgentToolExpander.Config(router, message, full, live, notice, max));
    }

    private static ToolRegistry focusedRegistry(ToolRegistry full, AgentRequestRouter.Route route) {
        return AgentToolPacks.select(full, AgentRequestRouter.Decision.focused(route, Map.of(), 0.9));
    }

    private static MockedStatic<AiConfig> defaults() {
        MockedStatic<AiConfig> config = mockStatic(AiConfig.class);
        config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
