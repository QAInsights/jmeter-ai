package org.qainsights.jmeter.ai.gui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.Action;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.text.AbstractDocument;
import javax.swing.undo.UndoManager;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.utils.Constants;

/**
 * A {@link JTextArea} that paints a ghost "placeholder" hint when it is empty
 * and unfocused content-wise - the standard composer affordance from modern
 * chat tools. The hint is painted (never inserted into the document), so it
 * never interferes with key handling, IME composition, or the message text.
 */
public class PlaceholderTextArea extends JTextArea {

    private String placeholder = "";
    private Action undoFallback;
    private Action redoFallback;

    public PlaceholderTextArea(int rows, int columns) {
        super(rows, columns);
        // Stock JTextArea keymaps bind only plain Enter to insert-break, so
        // Shift+Enter (the composer's advertised "newline" affordance) did
        // nothing. Bind it explicitly to the same action.
        getInputMap().put(
            javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.KeyEvent.SHIFT_DOWN_MASK
            ),
            "insert-break"
        );
    }

    public String getPlaceholder() {
        return placeholder;
    }

    void setUndoRedoFallbacks(Action undo, Action redo) {
        undoFallback = undo;
        redoFallback = redo;
    }

    @Override
    protected boolean processKeyBinding(KeyStroke key, KeyEvent event, int condition, boolean pressed) {
        boolean undo = Constants.UNDO_KEY_STROKE.equals(key);
        Action fallback = undo ? undoFallback : Constants.REDO_KEY_STROKE.equals(key) ? redoFallback : null;
        if (condition == WHEN_FOCUSED && pressed && isEnabled() && fallback != null) {
            // JMeter's text actions stay enabled even when their history is empty.
            boolean hasTextEdit = false;
            if (getDocument() instanceof AbstractDocument document) {
                for (var listener : document.getUndoableEditListeners()) {
                    if (listener instanceof UndoManager manager && (undo ? manager.canUndo() : manager.canRedo())) {
                        hasTextEdit = true;
                        break;
                    }
                }
            }
            if (!hasTextEdit && fallback.isEnabled()) {
                fallback.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, null,
                        event.getWhen(), event.getModifiersEx()));
                return true;
            }
        }
        return super.processKeyBinding(key, event, condition, pressed);
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!getText().isEmpty() || placeholder.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            g2.setColor(ThemeColors.secondaryText());
            g2.setFont(getFont().deriveFont(java.awt.Font.PLAIN));
            Insets insets = getInsets();
            var fm = g2.getFontMetrics();
            g2.drawString(
                placeholder,
                insets.left,
                insets.top + fm.getAscent()
            );
        } finally {
            g2.dispose();
        }
    }
}
