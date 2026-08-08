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
    void userControlledNameAndDescriptionAreHtmlEscaped() {
        IntellisensePopup.SuggestionCellRenderer renderer =
                new IntellisensePopup.SuggestionCellRenderer(() -> name -> "<script>alert(1)</script>");
        JLabel label = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "<b>evil</b> & \"co\"", 0, false, false);
        String text = label.getText();
        assertFalse(text.contains("<b>evil</b>"));
        assertFalse(text.contains("<script>"));
        assertTrue(text.contains("&lt;b&gt;evil&lt;/b&gt;"));
        assertTrue(text.contains("&lt;script&gt;"));
        assertTrue(text.contains("&amp;"));
        assertTrue(text.contains("&quot;co&quot;"));
    }

    @Test
    void htmlPrefixedNameWithoutDescriptionIsNotRenderedAsHtml() {
        IntellisensePopup.SuggestionCellRenderer renderer =
                new IntellisensePopup.SuggestionCellRenderer(() -> name -> null);
        JLabel label = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "<html><b>evil</b>", 0, false, false);
        String text = label.getText();
        assertFalse(text.contains("<b>evil</b>"));
        assertTrue(text.contains("&lt;html&gt;&lt;b&gt;evil&lt;/b&gt;"));
    }

    @Test
    void escapeHtmlEscapesAllSpecialCharacters() {
        assertEquals("&lt;&gt;&amp;&quot;&#39;",
                IntellisensePopup.SuggestionCellRenderer.escapeHtml("<>&\"'"));
        assertEquals("plain text",
                IntellisensePopup.SuggestionCellRenderer.escapeHtml("plain text"));
    }

    @Test
    void aboveYOpensUpwardAndMayGoNegative() {
        assertEquals(80, IntellisensePopup.aboveY(200, 120));
        // docking above the input's top edge needs a negative y so the popup
        // rises over the transcript instead of extending down over the input
        assertEquals(-70, IntellisensePopup.aboveY(50, 120));
        assertEquals(-120, IntellisensePopup.aboveY(0, 120));
    }

    @Test
    void providerReturnsDescriptionsForAllCommands() {
        for (String cmd : new String[]{"@code", "@wrap", "@lint", "@usage", "@optimize", "@this", "@testplan"}) {
            assertFalse(CommandIntellisenseProvider.getDescription(cmd).isEmpty(), cmd);
        }
        assertEquals("", CommandIntellisenseProvider.getDescription("@unknown"));
    }
}
