package org.qainsights.jmeter.ai.intellisense;

import javax.swing.JLabel;
import javax.swing.JList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionCellRendererTest {

    @Test
    void knownCommandRendersTwoLineHtml() {
        IntellisensePopup.SuggestionCellRenderer renderer =
                new IntellisensePopup.SuggestionCellRenderer();
        JLabel label = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "@lint", 0, false, false);
        assertTrue(label.getText().startsWith("<html>"));
        assertTrue(label.getText().contains("<b>@lint</b>"));
        assertTrue(label.getText().contains("meaningful names"));
    }

    @Test
    void unknownSuggestionRendersPlainSingleLine() {
        IntellisensePopup.SuggestionCellRenderer renderer =
                new IntellisensePopup.SuggestionCellRenderer();
        JLabel label = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "plain suggestion", 0, false, false);
        assertEquals("plain suggestion", label.getText());
    }

    @Test
    void providerReturnsDescriptionsForAllCommands() {
        for (String cmd : new String[]{"@code", "@wrap", "@lint", "@usage", "@optimize", "@this", "@testplan"}) {
            assertFalse(CommandIntellisenseProvider.getDescription(cmd).isEmpty(), cmd);
        }
        assertEquals("", CommandIntellisenseProvider.getDescription("@unknown"));
    }
}
