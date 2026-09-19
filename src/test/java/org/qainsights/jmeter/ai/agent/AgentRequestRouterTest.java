package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRequestRouterTest {

    @Test
    void routeDisplayNamesAreUserFacing() {
        assertEquals("Inspect and explain", AgentRequestRouter.Route.INSPECT_EXPLAIN.displayName());
        assertEquals("Correlation workflow", AgentRequestRouter.Route.CORRELATE.displayName());
    }

    @Test
    void decisionFactoriesSetExpectedOutcomesAndCopyProbabilities() {
        Map<String, Double> probabilities = new LinkedHashMap<>();
        probabilities.put("EDIT_TEST_PLAN", 0.9);
        AgentRequestRouter.Decision focused = AgentRequestRouter.Decision.focused(
                AgentRequestRouter.Route.EDIT_TEST_PLAN, probabilities, 0.8);
        probabilities.put("EDIT_TEST_PLAN", 0.1);

        assertTrue(focused.isFocused());
        assertEquals(0.9, focused.probabilities().get("EDIT_TEST_PLAN"));
        assertThrows(UnsupportedOperationException.class,
                () -> focused.probabilities().put("RUN_DIAGNOSE", 0.1));

        AgentRequestRouter.Decision all = AgentRequestRouter.Decision.allTools(
                AgentRequestRouter.Route.RUN_DIAGNOSE, Map.of(), 0.4);
        assertFalse(all.isFocused());
        assertEquals(AgentRequestRouter.Outcome.ALL_TOOLS, all.outcome());

        AgentRequestRouter.Decision unavailable = AgentRequestRouter.Decision.unavailable();
        assertEquals(AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR, unavailable.route());
        assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, unavailable.outcome());
    }

    @Test
    void noticeCopiesToolsAndKeepsTotalAtLeastSelectedCount() {
        List<String> tools = new ArrayList<>(List.of("one", "two"));
        AgentRequestRouter.Notice notice = new AgentRequestRouter.Notice(
                AgentRequestRouter.Decision.unavailable(), tools, 1);
        tools.clear();

        assertEquals(List.of("one", "two"), notice.toolNames());
        assertEquals(2, notice.totalToolCount());
        assertThrows(UnsupportedOperationException.class, () -> notice.toolNames().add("three"));
    }

    @Test
    void expansionNoticeCarriesAddedToolsAndIsExpansion() {
        AgentRequestRouter.Notice notice = AgentRequestRouter.Notice.expansion(
                AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.RUN_DIAGNOSE, Map.of(), 0.9),
                List.of("get_tree_state", "run_test"), 21,
                new ArrayList<>(List.of("run_test", "stop_test")));

        assertTrue(notice.isExpansion());
        assertEquals(List.of("run_test", "stop_test"), notice.addedToolNames());
        assertThrows(UnsupportedOperationException.class,
                () -> notice.addedToolNames().add("other"));
    }

    @Test
    void plainNoticeIsNotAnExpansion() {
        AgentRequestRouter.Notice notice = new AgentRequestRouter.Notice(
                AgentRequestRouter.Decision.unavailable(), List.of("get_tree_state"), 21);

        assertFalse(notice.isExpansion());
        assertEquals(List.of(), notice.addedToolNames());
    }
}
