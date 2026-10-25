package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.qainsights.jmeter.ai.fix.FixResult;
import org.qainsights.jmeter.ai.fix.PlanDiff;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixResultCardTest {
    @Test
    void noOpResultsShowKeepOnlyAndDoNotOfferUndo() {
        for (FixResult.Status status : List.of(
                FixResult.Status.ALREADY_PASSING, FixResult.Status.NOTHING_RAN)) {
            FixResult result = new FixResult(status, 0, 1, 0, 1,
                    0, 0, new File("backup.jmx"), List.of(), List.of(), "");
            FixResultCard card = new FixResultCard(result, () -> true, () -> { }, () -> { });

            assertFalse(result.hasUndoableChanges());
            assertEquals(List.of("Keep"), buttonNames(card));
        }
    }

    @Test
    void noUndoableRoundDoesNotOfferUndoActions() {
        FixResult result = new FixResult(FixResult.Status.NOT_FIXED, 1, 1, 1, 1,
                2, 2, new File("backup.jmx"), List.of(), List.of(), "");
        FixResultCard card = new FixResultCard(result, () -> true, () -> { }, () -> { });

        assertFalse(result.hasUndoableChanges());
        assertEquals(List.of("Keep"), buttonNames(card));
    }

    @Test
    void reportFileAddsOpenReportAction() throws Exception {
        FixResult result = new FixResult(FixResult.Status.FIXED, 1, 1, 0, 1,
                1, 0, new File("backup.jmx"), List.of(), List.of(), "");
        result.setReportFile(File.createTempFile("fix-report", ".md"));

        FixResultCard card = new FixResultCard(result, () -> true, () -> { }, () -> { });

        assertTrue(buttonNames(card).contains("Open report"));
    }

    @Test
    void countsAddedAssertionsAndResultCollectorsByGuiClass() {
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
        PlanDiff diff = PlanDiff.between(java.util.Map.of(), PlanDiff.snapshot(tree));
        FixResult result = new FixResult(FixResult.Status.FIXED, 1, 1, 0, 1,
                1, 0, null, List.of(), List.of(), "", diff, List.of());

        FixResultCard card = new FixResultCard(result, () -> true, () -> { }, () -> { });
        List<String> labels = labelTexts(card);

        assertTrue(labels.stream().anyMatch(label -> label.contains("Assertions added: 1")), labels.toString());
        assertTrue(labels.stream().anyMatch(label -> label.contains("Listeners added: 2")), labels.toString());
    }

    private static List<String> buttonNames(JPanel panel) {
        List<String> names = new ArrayList<>();
        collectButtons(panel, names);
        return names;
    }

    private static List<String> labelTexts(JPanel panel) {
        List<String> texts = new ArrayList<>();
        collectLabels(panel, texts);
        return texts;
    }

    private static void collectLabels(Component component, List<String> texts) {
        if (component instanceof JLabel label) {
            texts.add(label.getText());
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectLabels(child, texts);
            }
        }
    }

    private static ResultCollector listener(String name, String guiClass) {
        ResultCollector listener = new ResultCollector();
        listener.setName(name);
        listener.setProperty(TestElement.GUI_CLASS, guiClass);
        return listener;
    }

    private static void collectButtons(Component component, List<String> names) {
        if (component instanceof JButton button) {
            names.add(button.getText());
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectButtons(child, names);
            }
        }
    }
}
