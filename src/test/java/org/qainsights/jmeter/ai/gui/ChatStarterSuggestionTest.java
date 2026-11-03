package org.qainsights.jmeter.ai.gui;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatStarterSuggestionTest {
    @Test
    void controllerCsvMustCoverEveryEnabledSamplerBeforeSuggestingReview() {
        JMeterTreeNode root = node(new TestPlan());
        JMeterTreeNode group = node(new ThreadGroup());
        JMeterTreeNode controller = node(new org.apache.jmeter.control.GenericController());
        root.add(group);
        group.add(controller);
        JMeterTreeNode csv = node(new CSVDataSet());
        controller.add(csv);
        controller.add(node(new DebugSampler()));
        assertEquals("Review this Thread Group", ChatStarterSuggestion.fromTree(root, group).label());
        csv.setEnabled(false);
        assertEquals("Add a CSV data set", ChatStarterSuggestion.fromTree(root, group).label());
        csv.setEnabled(true);
        JMeterTreeNode uncovered = node(new DebugSampler());
        group.add(uncovered);
        assertEquals("Add a CSV data set", ChatStarterSuggestion.fromTree(root, group).label());
        uncovered.setEnabled(false);
        assertEquals("Review this Thread Group", ChatStarterSuggestion.fromTree(root, group).label());
        controller.setEnabled(false);
        uncovered.setEnabled(true);
        assertEquals("Add a CSV data set", ChatStarterSuggestion.fromTree(root, group).label());
    }

    @Test
    void duplicateGroupNamesGetDistinctTreePositionsInEveryPrompt() {
        JMeterTreeNode root = node(new TestPlan());
        JMeterTreeNode disabled = node(new ThreadGroup());
        disabled.setEnabled(false);
        root.add(disabled);
        JMeterTreeNode first = node(new ThreadGroup());
        JMeterTreeNode second = node(new ThreadGroup());
        first.setName("Checkout");
        second.setName("Checkout");
        root.add(first);
        root.add(second);
        for (int state = 0; state < 3; state++) {
            assertTrue(ChatStarterSuggestion.optionsFromTree(root, first).stream()
                    .allMatch(option -> option.prompt().contains("Thread Group 2 in depth-first tree order")));
            assertTrue(ChatStarterSuggestion.optionsFromTree(root, second).stream()
                    .allMatch(option -> option.prompt().contains("Thread Group 3 in depth-first tree order")));
            assertOptions(root, first);
            assertOptions(root, second);
            if (state == 0) {
                first.add(node(new DebugSampler()));
                second.add(node(new DebugSampler()));
            } else if (state == 1) {
                first.add(node(new CSVDataSet()));
                second.add(node(new CSVDataSet()));
            }
        }
        second.setName("Payment");
        assertTrue(ChatStarterSuggestion.optionsFromTree(root, first).stream()
                .noneMatch(option -> option.prompt().contains("depth-first tree order")));
    }

    @Test
    void offersThreeDistinctSafeDraftsForEachPlanState() {
        JMeterTreeNode root = node(new TestPlan());
        assertOptions(root, null);
        assertOptions(null, null);
        JMeterTreeNode disabled = node(new ThreadGroup());
        disabled.setEnabled(false);
        root.add(disabled);
        JMeterTreeNode group = node(new ThreadGroup());
        group.setName("Checkout");
        root.add(group);
        assertEquals("Add a request", ChatStarterSuggestion.optionsFromTree(root, null).get(0).label());
        assertTrue(ChatStarterSuggestion.optionsFromTree(root, null).get(0).prompt().contains("Checkout"));
        assertOptions(root, group);
        group.add(node(new DebugSampler()));
        assertOptions(root, group);
        group.add(node(new CSVDataSet()));
        java.util.List<ChatStarterSuggestion> options = ChatStarterSuggestion.optionsFromTree(root, group);
        assertEquals(java.util.List.of("Review this Thread Group", "Review response checks", "Plan the workload"),
                options.stream().map(ChatStarterSuggestion::label).toList());
        assertTrue(options.stream().allMatch(option -> option.prompt().contains("Checkout")));
        assertOptions(root, group);
        assertOptions(root, disabled);
    }

    private void assertOptions(JMeterTreeNode root, JMeterTreeNode selected) {
        java.util.List<ChatStarterSuggestion> options = ChatStarterSuggestion.optionsFromTree(root, selected);
        assertEquals(3, options.size());
        assertEquals(3, options.stream().map(ChatStarterSuggestion::label).distinct().count());
        for (ChatStarterSuggestion option : options) {
            assertNull(org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler.processElementRequest(option.prompt()));
        }
    }

    @Test
    void planWithOnlyDisabledGroupsFallsBackToPlanReview() {
        JMeterTreeNode root = node(new TestPlan());
        JMeterTreeNode group = node(new ThreadGroup());
        root.add(group);
        group.setEnabled(false);
        assertEquals("Review this test plan", ChatStarterSuggestion.fromTree(root, null).label());
        assertEquals("Review this test plan", ChatStarterSuggestion.fromTree(root, group).label());
        assertOptions(root, null);
    }
    @Test
    void creationDraftsBypassTheDirectElementCommandParser() {
        JMeterTreeNode root = node(new TestPlan());
        assertNull(org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler.processElementRequest(
                ChatStarterSuggestion.fromTree(root, null).prompt()));
        JMeterTreeNode group = node(new ThreadGroup());
        root.add(group);
        assertNull(org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler.processElementRequest(
                ChatStarterSuggestion.fromTree(root, group).prompt()));
        JMeterTreeNode sampler = node(new DebugSampler());
        group.add(sampler);
        assertNull(org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler.processElementRequest(
                ChatStarterSuggestion.fromTree(root, group).prompt()));
        sampler.add(node(new CSVDataSet()));
        assertEquals("Add a CSV data set", ChatStarterSuggestion.fromTree(root, group).label());
    }
    private JMeterTreeNode node(TestElement element) {
        element.setEnabled(true);
        return new JMeterTreeNode(element, null);
    }

    @Test
    void emptyPlanSuggestsAGroupAndUnavailablePlanSuggestsReview() {
        assertEquals("Create a Thread Group", ChatStarterSuggestion.fromTree(node(new TestPlan()), null).label());
        assertEquals("Review this test plan", ChatStarterSuggestion.fromTree(null, null).label());
    }

    @Test
    void followsSelectedGroupAndDoesNotChangeTheTree() {
        JMeterTreeNode root = node(new TestPlan());
        JMeterTreeNode first = node(new ThreadGroup());
        JMeterTreeNode second = node(new ThreadGroup());
        second.setName("Checkout");
        root.add(first);
        root.add(second);
        assertEquals("Add a request", ChatStarterSuggestion.fromTree(root, first).label());
        JMeterTreeNode sampler = node(new DebugSampler());
        second.add(sampler);
        ChatStarterSuggestion suggestion = ChatStarterSuggestion.fromTree(root, sampler);
        assertEquals("Add a CSV data set", suggestion.label());
        assertTrue(suggestion.prompt().contains("Checkout"));
        assertEquals(1, second.getChildCount());
        second.add(node(new CSVDataSet()));
        assertEquals("Review this Thread Group", ChatStarterSuggestion.fromTree(root, sampler).label());
    }

    @Test
    void inheritedCsvCountsButDisabledAndOtherGroupsCsvDoNot() {
        JMeterTreeNode root = node(new TestPlan());
        JMeterTreeNode group = node(new ThreadGroup());
        root.add(group);
        group.add(node(new DebugSampler()));
        JMeterTreeNode csv = node(new CSVDataSet());
        root.add(csv);
        assertEquals("Review this Thread Group", ChatStarterSuggestion.fromTree(root, group).label());
        csv.setEnabled(false);
        assertEquals("Add a CSV data set", ChatStarterSuggestion.fromTree(root, group).label());
        root.remove(csv);
        JMeterTreeNode other = node(new ThreadGroup());
        root.add(other);
        other.add(csv);
        csv.setEnabled(true);
        assertEquals("Add a CSV data set", ChatStarterSuggestion.fromTree(root, group).label());
        group.setEnabled(false);
        assertEquals("Review this test plan", ChatStarterSuggestion.fromTree(root, group).label());
    }
}
