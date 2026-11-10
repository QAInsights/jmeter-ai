package org.qainsights.jmeter.ai.gui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.PlainDocument;
import org.apache.jorphan.gui.ui.TextAreaUIWithUndo;
import org.qainsights.jmeter.ai.utils.Constants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderTextAreaTest {

    @Test
    void jmeterTextBindingsConsumeShortcutsEvenWithEmptyHistory() throws Exception {
        withJMeterTextUi(() -> {
            PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
            for (KeyStroke key : new KeyStroke[]{Constants.UNDO_KEY_STROKE, Constants.REDO_KEY_STROKE}) {
                var action = area.getActionMap().get(area.getInputMap().get(key));
                assertTrue(action.isEnabled());
                assertTrue(press(area, key));
                assertEquals("", area.getText());
            }
        });
    }

    @Test
    void undoAndRedoPreferTextEditsAndOtherwiseRunPlanActions() throws Exception {
        withJMeterTextUi(() -> {
            PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
            AtomicInteger planUndos = new AtomicInteger();
            AtomicInteger planRedos = new AtomicInteger();
            area.setUndoRedoFallbacks(countingAction(planUndos), countingAction(planRedos));
            area.append("draft");

            assertTrue(press(area, Constants.REDO_KEY_STROKE));
            assertEquals(1, planRedos.get());
            assertEquals("draft", area.getText());
            assertTrue(press(area, Constants.UNDO_KEY_STROKE));
            assertEquals("", area.getText());
            assertEquals(0, planUndos.get());
            assertTrue(press(area, Constants.UNDO_KEY_STROKE));
            assertEquals(1, planUndos.get());
            assertTrue(press(area, Constants.REDO_KEY_STROKE));
            assertEquals("draft", area.getText());
            assertEquals(1, planRedos.get());
            assertTrue(press(area, Constants.REDO_KEY_STROKE));
            assertEquals(2, planRedos.get());
        });
    }

    @Test
    void emptyAndReplacedDocumentsUsePlanFallbacks() throws Exception {
        withJMeterTextUi(() -> {
            PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
            AtomicInteger calls = new AtomicInteger();
            area.setUndoRedoFallbacks(countingAction(calls), countingAction(calls));
            assertTrue(press(area, Constants.UNDO_KEY_STROKE));
            assertTrue(press(area, Constants.REDO_KEY_STROKE));
            area.append("old document");
            area.setDocument(new PlainDocument());
            assertTrue(press(area, Constants.UNDO_KEY_STROKE));
            assertTrue(press(area, Constants.REDO_KEY_STROKE));
            assertEquals(4, calls.get());
            area.append("new document");
            assertTrue(press(area, Constants.UNDO_KEY_STROKE));
            assertEquals("", area.getText());
            assertEquals(4, calls.get());
        });
    }

    @Test
    void fallbackDoesNotHandleOtherFocusScopesOrUnrelatedKeys() throws Exception {
        withJMeterTextUi(() -> {
            PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
            AtomicInteger calls = new AtomicInteger();
            area.setUndoRedoFallbacks(countingAction(calls), countingAction(calls));
            KeyStroke undo = Constants.UNDO_KEY_STROKE;
            assertFalse(area.processKeyBinding(undo, keyEvent(area, undo),
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, true));
            assertFalse(area.processKeyBinding(undo, keyEvent(area, undo),
                    JComponent.WHEN_IN_FOCUSED_WINDOW, true));
            KeyStroke released = KeyStroke.getKeyStroke(undo.getKeyCode(), undo.getModifiers(), true);
            assertFalse(area.processKeyBinding(released, new KeyEvent(area, KeyEvent.KEY_RELEASED, 0,
                    undo.getModifiers(), undo.getKeyCode(), KeyEvent.CHAR_UNDEFINED),
                    JComponent.WHEN_FOCUSED, false));
            assertTrue(press(area, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK)));
            assertEquals("\n", area.getText());
            assertEquals(0, calls.get());
        });
    }

    @Test
    void disabledFallbackIsNotInvoked() throws Exception {
        withJMeterTextUi(() -> {
            PlaceholderTextArea area = new PlaceholderTextArea(3, 20);
            AtomicInteger calls = new AtomicInteger();
            AbstractAction disabled = countingAction(calls);
            disabled.setEnabled(false);
            area.setUndoRedoFallbacks(disabled, disabled);
            assertTrue(press(area, Constants.UNDO_KEY_STROKE));
            assertTrue(press(area, Constants.REDO_KEY_STROKE));
            assertEquals(0, calls.get());
        });
    }

    private static AbstractAction countingAction(AtomicInteger calls) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                calls.incrementAndGet();
            }
        };
    }

    private static boolean press(PlaceholderTextArea area, KeyStroke key) {
        return area.processKeyBinding(key, keyEvent(area, key), JComponent.WHEN_FOCUSED, true);
    }

    private static KeyEvent keyEvent(PlaceholderTextArea area, KeyStroke key) {
        return new KeyEvent(area, KeyEvent.KEY_PRESSED, 0, key.getModifiers(), key.getKeyCode(),
                KeyEvent.CHAR_UNDEFINED);
    }

    private static void withJMeterTextUi(Runnable test) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object original = UIManager.get("TextAreaUI");
            Object backup = UIManager.get(TextAreaUIWithUndo.BACKUP_UI_CLASS);
            try {
                TextAreaUIWithUndo.install(UIManager.getDefaults());
                test.run();
            } finally {
                UIManager.put("TextAreaUI", original);
                UIManager.put(TextAreaUIWithUndo.BACKUP_UI_CLASS, backup);
            }
        });
    }

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
