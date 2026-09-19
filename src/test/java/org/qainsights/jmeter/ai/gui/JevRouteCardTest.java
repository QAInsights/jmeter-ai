package org.qainsights.jmeter.ai.gui;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.AgentRequestRouter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevRouteCardTest {

    @Test
    void focusedCardClearlyNamesJevRouteConfidenceToolsAndModel() {
        AgentRequestRouter.Decision decision = AgentRequestRouter.Decision.focused(
                AgentRequestRouter.Route.CORRELATE, Map.of("CORRELATE", 0.94), 0.92);
        JevRouteCard card = new JevRouteCard(
                new AgentRequestRouter.Notice(decision,
                        List.of("get_tree_state", "find_correlation_candidates"), 21),
                "openai:gpt-5");

        assertTrue(card.getHeaderText().contains("Jev Smart Route"));
        assertTrue(card.getSummaryText().contains("Correlation workflow"));
        assertTrue(card.getSummaryText().contains("92%"));
        assertTrue(card.getResponsibilityText().contains("2 focused tools of 21"));
        assertTrue(card.getResponsibilityText().contains("gpt-5"));
        assertTrue(card.getDetailsText().contains("Jev selected the tool family"));
        assertTrue(card.getDetailsText().contains("OpenAI"));
        assertTrue(card.getDetailsText().contains("find_correlation_candidates"));
    }

    @Test
    void allToolsAndUnavailableStatesAreExplicit() {
        JevRouteCard uncertain = new JevRouteCard(new AgentRequestRouter.Notice(
                AgentRequestRouter.Decision.allTools(
                        AgentRequestRouter.Route.EDIT_TEST_PLAN, Map.of(), 0.5),
                List.of("get_tree_state"), 21), "claude-sonnet");
        JevRouteCard unavailable = new JevRouteCard(new AgentRequestRouter.Notice(
                AgentRequestRouter.Decision.unavailable(), List.of("get_tree_state"), 21),
                "deepseek:deepseek-chat");

        assertTrue(uncertain.getSummaryText().contains("Jev was uncertain"));
        assertTrue(uncertain.getDetailsText().contains("provided all Agent Mode tools"));
        assertTrue(unavailable.getSummaryText().contains("Jev unavailable"));
        assertTrue(unavailable.getResponsibilityText().contains("DeepSeek"));
        assertTrue(unavailable.getDetailsText().contains("Jev was unavailable"));
        assertFalse(unavailable.getDetailsText().contains("Jev selected"));
    }

    @Test
    void complexAndNullNoticesUseSafeAllToolPresentation() {
        JevRouteCard complex = new JevRouteCard(new AgentRequestRouter.Notice(
                AgentRequestRouter.Decision.allTools(
                        AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR, Map.of(), 0.9),
                List.of("get_tree_state"), 21), "google:gemini");
        JevRouteCard missing = new JevRouteCard(null, null);

        assertTrue(complex.getSummaryText().contains("Complex request"));
        assertTrue(missing.getSummaryText().contains("Jev unavailable"));
    }

    @Test
    void expansionCardShowsAddedRouteToolsAndCounts() {
        AgentRequestRouter.Notice notice = AgentRequestRouter.Notice.expansion(
                AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.RUN_DIAGNOSE, Map.of(), 0.87),
                List.of("get_tree_state", "run_test", "stop_test"), 21,
                List.of("run_test", "stop_test"));
        JevRouteCard card = new JevRouteCard(notice, "openai:gpt-5");

        assertTrue(card.getHeaderText().contains("Jev Expanded Tools"));
        assertFalse(card.getHeaderText().contains("Smart Route"));
        assertTrue(card.getSummaryText().contains("Run and diagnose added"));
        assertTrue(card.getSummaryText().contains("87%"));
        assertTrue(card.getResponsibilityText().contains("3 of 21 tools available"));
        assertTrue(card.getDetailsText().contains("Jev added the Run and diagnose tools"));
        assertTrue(card.getDetailsText().contains("Added tools:"));
        assertTrue(card.getDetailsText().contains("run_test"));
        assertFalse(card.getDetailsText().contains("get_tree_state"));
    }

    @Test
    void expansionFallbacksDescribeAllToolAndUnavailableStates() {
        JevRouteCard allTools = new JevRouteCard(AgentRequestRouter.Notice.expansion(
                AgentRequestRouter.Decision.allTools(
                        AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR, Map.of(), 0.4),
                List.of("a"), 21, List.of("b")), "meta:muse");
        JevRouteCard unavailable = new JevRouteCard(AgentRequestRouter.Notice.expansion(
                AgentRequestRouter.Decision.unavailable(),
                List.of("a"), 21, List.of("b")), "meta:muse");

        assertTrue(allTools.getSummaryText().contains("All Agent Mode tools enabled"));
        assertTrue(allTools.getDetailsText().contains("enabled all Agent Mode tools"));
        assertTrue(unavailable.getSummaryText().contains("Jev unavailable"));
        assertTrue(unavailable.getSummaryText().contains("all Agent Mode tools enabled"));
        assertTrue(unavailable.getDetailsText().contains("Jev was unavailable"));
    }

    @Test
    void longModelNamesRenderProviderFirstWithoutTruncation() {
        JevRouteCard card = new JevRouteCard(new AgentRequestRouter.Notice(
                AgentRequestRouter.Decision.focused(
                        AgentRequestRouter.Route.EDIT_TEST_PLAN, Map.of(), 0.9),
                List.of("get_tree_state", "add_element"), 21),
                "meta:muse-spark-1.2-contributor");

        assertTrue(card.getResponsibilityText().contains("Meta muse-spark-1.2-contributor"));
        assertFalse(card.getResponsibilityText().contains("·"));
        assertFalse(card.getResponsibilityText().contains("…"));
        assertTrue(card.getDetailsText().contains("Meta muse-spark-1.2-contributor"));
        assertFalse(card.getDetailsText().contains("·"));
    }

    @Test
    void expansionAndThemeAreSafe() {
        JevRouteCard card = new JevRouteCard(new AgentRequestRouter.Notice(
                AgentRequestRouter.Decision.unavailable(), List.of(), 0), "meta:muse");

        assertFalse(card.isExpanded());
        card.setExpanded(true);
        assertTrue(card.isExpanded());
        assertTrue(card.getHeaderText().startsWith("▾"));
        assertDoesNotThrow(card::applyTheme);
    }
}
