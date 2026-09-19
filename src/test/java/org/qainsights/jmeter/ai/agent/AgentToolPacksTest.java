package org.qainsights.jmeter.ai.agent;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.handlers.ApplyCorrelationHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.GetTestResultsHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.OpenPlanHandler;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolPacksTest {

    private static final Set<String> READ_TOOLS = Set.of(
            ReadToolHandlers.GET_TREE_STATE,
            ReadToolHandlers.GET_ELEMENT_CONFIG,
            ReadToolHandlers.GET_ELEMENT_CHILDREN,
            ReadToolHandlers.GET_ELEMENT_SCHEMA);

    @Test
    void specializedPacksContainReadToolsAndExpectedSpecialists() {
        assertTrue(AgentToolPacks.toolNames(AgentRequestRouter.Route.INSPECT_EXPLAIN).containsAll(READ_TOOLS));
        assertTrue(AgentToolPacks.toolNames(AgentRequestRouter.Route.EDIT_TEST_PLAN)
                .contains(DeleteElementHandler.DELETE_ELEMENT));
        assertTrue(AgentToolPacks.toolNames(AgentRequestRouter.Route.RUN_DIAGNOSE)
                .contains(GetTestResultsHandler.GET_TEST_RESULTS));
        assertTrue(AgentToolPacks.toolNames(AgentRequestRouter.Route.CORRELATE)
                .contains(ApplyCorrelationHandler.APPLY_CORRELATION));
        assertTrue(AgentToolPacks.toolNames(AgentRequestRouter.Route.PLAN_FILES)
                .contains(OpenPlanHandler.OPEN_PLAN));
        for (AgentRequestRouter.Route route : List.of(
                AgentRequestRouter.Route.INSPECT_EXPLAIN,
                AgentRequestRouter.Route.EDIT_TEST_PLAN,
                AgentRequestRouter.Route.RUN_DIAGNOSE,
                AgentRequestRouter.Route.CORRELATE,
                AgentRequestRouter.Route.PLAN_FILES)) {
            assertTrue(AgentToolPacks.toolNames(route).containsAll(READ_TOOLS));
        }
        assertTrue(AgentToolPacks.toolNames(AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR).isEmpty());
    }

    @Test
    void focusedSelectionFiltersAdvertisedAndExecutableRegistryInDefaultOrder() {
        ToolRegistry full = AgentToolRegistry.createDefault();
        AgentRequestRouter.Decision decision = AgentRequestRouter.Decision.focused(
                AgentRequestRouter.Route.CORRELATE, java.util.Map.of(), 0.9);

        ToolRegistry selected = AgentToolPacks.select(full, decision);
        List<String> selectedNames = selected.getSpecs().stream().map(spec -> spec.getName()).toList();
        List<String> expectedOrder = full.getSpecs().stream().map(spec -> spec.getName())
                .filter(AgentToolPacks.toolNames(AgentRequestRouter.Route.CORRELATE)::contains).toList();

        assertEquals(expectedOrder, selectedNames);
        assertEquals(AgentToolPacks.toolNames(AgentRequestRouter.Route.CORRELATE).size(), selected.size());
        assertTrue(selected.isRegistered(ApplyCorrelationHandler.APPLY_CORRELATION));
        assertFalse(selected.isRegistered(DeleteElementHandler.DELETE_ELEMENT));
    }

    @Test
    void allToolsAndUnavailableDecisionsReturnOriginalRegistry() {
        ToolRegistry full = AgentToolRegistry.createDefault();

        assertSame(full, AgentToolPacks.select(full, AgentRequestRouter.Decision.allTools(
                AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR, java.util.Map.of(), 0.9)));
        assertSame(full, AgentToolPacks.select(full, AgentRequestRouter.Decision.unavailable()));
        assertSame(full, AgentToolPacks.select(full, null));
    }

    @Test
    void nullRegistryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentToolPacks.select(null, AgentRequestRouter.Decision.unavailable()));
    }
}
