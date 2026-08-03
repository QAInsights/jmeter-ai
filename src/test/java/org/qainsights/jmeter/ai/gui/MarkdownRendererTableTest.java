package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for table and `<br>` handling in {@link MarkdownRenderer}:
 * a markdown table renders as a grid (not literal pipes) and HTML breaks
 * become newlines.
 */
class MarkdownRendererTableTest {

    private static StyledDocument render(String markdown) throws Exception {
        StyledDocument doc = new DefaultStyledDocument();
        new MarkdownRenderer().process(doc, markdown);
        return doc;
    }

    private static int gridCount(StyledDocument doc) {
        int count = 0;
        for (int offset = 0; offset < doc.getLength(); offset++) {
            Element leaf = doc.getCharacterElement(offset);
            java.awt.Component component = StyleConstants.getComponent(leaf.getAttributes());
            if (component instanceof JPanel && ((JPanel) component).getLayout() instanceof java.awt.GridLayout) {
                count++;
            }
        }
        return count;
    }

    @Test
    void markdownTableRendersAsGrid() throws Exception {
        StyledDocument doc = render("Intro line\n"
                + "| Level | Logger | Impact |\n"
                + "| --- | --- | --- |\n"
                + "| ERROR | GoogleAiService | models won't load |\n"
                + "| WARN | Plugin | missing from plan |\n"
                + "Trailing line");

        assertEquals(1, gridCount(doc));
        String text = doc.getText(0, doc.getLength());
        assertFalse(text.contains("| Level |"), "pipes must not leak into the rendered text");
        assertTrue(text.contains("Intro line"));
        assertTrue(text.contains("Trailing line"));
    }

    @Test
    void nonTablePipesAreLeftAlone() throws Exception {
        StyledDocument doc = render("use a | b like this\nnext line");
        assertEquals(0, gridCount(doc));
        assertTrue(doc.getText(0, doc.getLength()).contains("use a | b like this"));
    }

    @Test
    void htmlBreaksBecomeNewlines() throws Exception {
        StyledDocument doc = render("first<br>second<br/>third<br />fourth");
        String text = doc.getText(0, doc.getLength());
        assertFalse(text.contains("<br"));
        assertTrue(text.contains("first\nsecond"));
    }

    @Test
    void tableAtDocumentEnd() throws Exception {
        StyledDocument doc = render("| a | b |\n|---|---|\n| 1 | 2 |");
        assertEquals(1, gridCount(doc));
    }

    @Test
    void htmlBreakInsideInlineCodeSurvives() throws Exception {
        StyledDocument doc = render("use `<br>` for breaks<br>next line");
        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("<br>"), "the inline-code <br> must stay literal");
        assertTrue(text.contains("\nnext line"), "the prose <br> must become a newline");
    }

    @Test
    void replaceHtmlBreaksOutsideCodeRules() {
        assertEquals("a\nb", MarkdownRenderer.replaceHtmlBreaksOutsideCode("a<br>b"));
        assertEquals("x `<br/>` y", MarkdownRenderer.replaceHtmlBreaksOutsideCode("x `<br/>` y"));
        assertEquals("`code` \n after", MarkdownRenderer.replaceHtmlBreaksOutsideCode("`code` <br> after"));
    }
}
