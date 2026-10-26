package org.qainsights.jmeter.ai.fix;

import java.util.List;
import java.util.Map;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixReportTest {
    @Test
    void markdownContainsRequiredSectionsAndMultiUserSummary() {
        PlanDiff.ElementSnapshot sampler = new PlanDiff.ElementSnapshot("HTTPSamplerProxy", Map.of("path", "/old"));
        PlanDiff diff = PlanDiff.between(Map.of(), Map.of("Plan/Request", sampler));
        FixResult result = new FixResult(FixResult.Status.PARTLY_FIXED, 2, 4, 1, 4, 1, 0,
                null, List.of("NEEDS USER: provide a test account"), List.of(),
                FixLoop.CONCURRENCY_PARTIAL_REASON, diff, List.of(diff), true, 3, 8, 1, 8);
        result.setRoundSummaries(List.of("Round 1 targeted 2 of 4 failures; 1 of 4 remained."));
        result.setDeterministicHints(List.of(
                "Move literal password, passwd, or pwd argument values to User Defined Variables or a CSV Data Set."));
        result.setBeforeFailures(List.of(new org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Failure(
                "Request", "200", "Soft failure: page title indicates an error")));

        String markdown = FixReport.markdown(result);

        for (String section : List.of("## Before", "## What changed", "## Why", "## Assertions added",
                "## Listeners added", "## Needs you", "## Multi-user check")) {
            assertTrue(markdown.contains(section), section);
        }
        assertTrue(markdown.contains("2 users × 2 loops: 3 of 8 failed → 1 of 8 failed."));
        assertTrue(markdown.contains("NEEDS USER: provide a test account"));
        assertTrue(markdown.contains("Move literal password"));
        assertTrue(markdown.contains("Plan/Request"));
        assertTrue(markdown.contains("Soft failure: page title indicates an error"));
    }

    @Test
    void markdownIdentifiesAnInconclusiveMultiUserCheck() {
        FixResult result = new FixResult(FixResult.Status.ALREADY_PASSING, 0, 1, 0, 1,
                0, 0, null, List.of(), List.of(), "", null, List.of(), true, 0, 0, 0, 0);
        result.setConcurrencyReason("The multi-user check did not finish within 120 s.");

        String markdown = FixReport.markdown(result);

        assertTrue(markdown.contains("Inconclusive: The multi-user check did not finish within 120 s."));
        assertFalse(markdown.contains("2 users × 2 loops:"));
    }

    @Test
    void markdownListsAddedResultCollectorsAndAssertionsByGuiClass() {
        HashTree tree = new ListedHashTree();
        HashTree children = tree.add(new TestPlan("Test Plan"));
        children.add(listener("Make it pass - View Results Tree",
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer"));
        children.add(listener("Make it pass - Summary Report",
                "org.apache.jmeter.visualizers.SummaryReport"));
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setName("Make it pass: page check");
        assertion.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.assertions.gui.AssertionGui");
        children.add(assertion);
        PlanDiff diff = PlanDiff.between(Map.of(), PlanDiff.snapshot(tree));
        FixResult result = new FixResult(FixResult.Status.FIXED, 1, 1, 0, 1, 1, 0,
                null, List.of(), List.of(), "", diff, List.of());

        String markdown = FixReport.markdown(result);

        assertTrue(markdown.contains("- Test Plan/Make it pass: page check"));
        assertTrue(markdown.contains("- Test Plan/Make it pass - View Results Tree"));
        assertTrue(markdown.contains("- Test Plan/Make it pass - Summary Report"));
        assertFalse(markdown.contains("`TestElement.gui_class`"));
        assertFalse(markdown.contains("`TestElement.test_class`"));
        assertFalse(markdown.contains("`` → ``"));
    }

    @Test
    void changedElementsOmitOnlyClassProperties() {
        PlanDiff.ElementSnapshot before = new PlanDiff.ElementSnapshot("HTTPSamplerProxy", Map.of(
                TestElement.GUI_CLASS, "before-gui", TestElement.TEST_CLASS, "before-class",
                "path", "/old", "empty-value", "before"));
        PlanDiff.ElementSnapshot after = new PlanDiff.ElementSnapshot("HTTPSamplerProxy", Map.of(
                TestElement.GUI_CLASS, "after-gui", TestElement.TEST_CLASS, "after-class",
                "path", "/new", "empty-value", ""));
        PlanDiff diff = PlanDiff.between(Map.of("Plan/Request", before), Map.of("Plan/Request", after));
        FixResult result = new FixResult(FixResult.Status.FIXED, 1, 1, 0, 1,
                1, 0, null, List.of(), List.of(), "", diff, List.of());

        String markdown = FixReport.markdown(result);

        assertTrue(markdown.contains("`path`: `/old` → `/new`"));
        assertTrue(markdown.contains("`empty-value`: `before` → ``"));
        assertFalse(markdown.contains("`TestElement.gui_class`"));
        assertFalse(markdown.contains("`TestElement.test_class`"));
    }

    private static ResultCollector listener(String name, String guiClass) {
        ResultCollector listener = new ResultCollector();
        listener.setName(name);
        listener.setProperty(TestElement.GUI_CLASS, guiClass);
        return listener;
    }
}
