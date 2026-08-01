package org.qainsights.jmeter.ai.gui;

import javax.swing.JTextPane;
import javax.swing.text.StyledDocument;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingIndicatorTest {

    @Test
    void startInsertsIndicatorAtDocumentEnd() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = pane.getStyledDocument();
        doc.insertString(0, "existing text\n", null);

        ThinkingIndicator indicator = new ThinkingIndicator();
        indicator.start(doc);

        assertTrue(indicator.isActive());
        String text = doc.getText(0, doc.getLength());
        assertTrue(text.startsWith("existing text\n"));
        assertTrue(text.contains(ThinkingIndicator.BASE_TEXT));

        indicator.stop(doc);
    }

    @Test
    void stopRemovesExactlyTheInsertedRange() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = pane.getStyledDocument();
        String original = "conversation so far\n";
        doc.insertString(0, original, null);

        ThinkingIndicator indicator = new ThinkingIndicator();
        indicator.start(doc);
        indicator.stop(doc);

        assertFalse(indicator.isActive());
        assertEquals(original, doc.getText(0, doc.getLength()));
    }

    @Test
    void startWhileActiveIsANoOp() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = pane.getStyledDocument();

        ThinkingIndicator indicator = new ThinkingIndicator();
        indicator.start(doc);
        int lengthAfterFirstStart = doc.getLength();
        indicator.start(doc); // second call must not insert again

        assertEquals(lengthAfterFirstStart, doc.getLength());
        indicator.stop(doc);
    }

    @Test
    void stopWhenInactiveIsANoOp() {
        JTextPane pane = new JTextPane();
        StyledDocument doc = pane.getStyledDocument();

        ThinkingIndicator indicator = new ThinkingIndicator();
        assertDoesNotThrow(() -> indicator.stop(doc));
        assertEquals(0, doc.getLength());
    }

    @Test
    void doubleStopIsSafe() throws Exception {
        JTextPane pane = new JTextPane();
        StyledDocument doc = pane.getStyledDocument();
        doc.insertString(0, "text\n", null);

        ThinkingIndicator indicator = new ThinkingIndicator();
        indicator.start(doc);
        indicator.stop(doc);
        assertDoesNotThrow(() -> indicator.stop(doc));
        assertEquals("text\n", doc.getText(0, doc.getLength()));
    }
}
