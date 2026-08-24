package org.qainsights.jmeter.ai.gui;

import java.awt.Font;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WelcomePanelTest {

    @Test
    void keepsWelcomeContentInADedicatedAccessibleSurface() {
        String markdown = "# Welcome to Feather Wand\n\nAsk about your test plan.";
        WelcomePanel panel = new WelcomePanel(
                markdown, new Font(Font.DIALOG, Font.PLAIN, 13));

        assertEquals(markdown, panel.getMarkdown());
        assertNotNull(findTextPane(panel));
        assertEquals(0, countLabels(panel));
        panel.applyTheme();
        panel.applyFont(new Font(Font.DIALOG, Font.PLAIN, 15));
    }

    @Test
    void preferredHeightAdaptsWhenTheTranscriptNarrows() {
        WelcomePanel panel = new WelcomePanel(
                "# Welcome\n\nA longer setup message that wraps across several lines "
                        + "when the chat panel becomes narrow.",
                new Font(Font.DIALOG, Font.PLAIN, 13));
        javax.swing.JPanel parent = new javax.swing.JPanel();
        parent.add(panel);
        parent.setSize(500, 600);
        int wideHeight = panel.getPreferredSize().height;

        parent.setSize(350, 600);
        int narrowHeight = panel.getPreferredSize().height;
        org.junit.jupiter.api.Assertions.assertTrue(narrowHeight >= wideHeight);
    }

    private static int countLabels(java.awt.Container root) {
        int count = 0;
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.JLabel) {
                count++;
            }
            if (component instanceof java.awt.Container container) {
                count += countLabels(container);
            }
        }
        return count;
    }

    private static javax.swing.JTextPane findTextPane(java.awt.Container root) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.JTextPane textPane) {
                return textPane;
            }
            if (component instanceof java.awt.Container container) {
                javax.swing.JTextPane found = findTextPane(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
