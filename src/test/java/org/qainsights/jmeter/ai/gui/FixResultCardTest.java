package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;

import org.qainsights.jmeter.ai.fix.FixResult;
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

    private static List<String> buttonNames(JPanel panel) {
        List<String> names = new ArrayList<>();
        collectButtons(panel, names);
        return names;
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
