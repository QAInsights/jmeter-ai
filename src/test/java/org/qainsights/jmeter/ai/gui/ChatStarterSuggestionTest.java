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
    void creationDraftsBypassTheDirectElementCommandParser() {
        JMeterTreeNode root = node(new TestPlan());
        assertNull(org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler.processElementRequest(
                ChatStarterSuggestion.fromTree(root, null).prompt()));
        JMeterTreeNode group = node(new ThreadGroup());
        root.add(group);
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
