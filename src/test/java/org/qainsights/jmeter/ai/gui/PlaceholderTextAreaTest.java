package org.qainsights.jmeter.ai.gui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderTextAreaTest {

    @Test
    void placeholderIsPaintOnlyAndNeverEntersDocument() {
        PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
        area.setPlaceholder("Ask about your test plan");
        assertEquals("Ask about your test plan", area.getPlaceholder());
        assertEquals("", area.getText());
    }

    @Test
    void nullPlaceholderBecomesEmpty() {
        PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
        area.setPlaceholder(null);
        assertEquals("", area.getPlaceholder());
    }

    @Test
    void paintsWithoutThrowingInAllStates() {
        PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
        area.setSize(200, 60);
        BufferedImage img = new BufferedImage(
            200, 60, BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g1 = img.createGraphics();
        assertDoesNotThrow(() -> area.paint(g1)); // empty, no placeholder
        g1.dispose();

        area.setPlaceholder("hint");
        Graphics2D g2 = img.createGraphics();
        assertDoesNotThrow(() -> area.paint(g2)); // empty, with placeholder
        g2.dispose();

        area.setText("typed text");
        Graphics2D g3 = img.createGraphics();
        assertDoesNotThrow(() -> area.paint(g3)); // non-empty: no placeholder
        g3.dispose();
    }

    @Test
    void shiftEnterInsertsNewline() {
        PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
        area.setText("hello");
        area.setCaretPosition(5);

        javax.swing.KeyStroke shiftEnter = javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_ENTER,
            java.awt.event.KeyEvent.SHIFT_DOWN_MASK
        );
        Object actionKey = area.getInputMap().get(shiftEnter);
        assertEquals("insert-break", actionKey,
            "Shift+Enter must be bound to the newline action");

        area.getActionMap().get(actionKey).actionPerformed(
            new java.awt.event.ActionEvent(
                area, java.awt.event.ActionEvent.ACTION_PERFORMED, "insert-break"));
        assertEquals("hello\n", area.getText());
    }
}
