package org.qainsights.jmeter.ai.intellisense;

import java.awt.Point;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.text.AttributedCharacterIterator;
import java.util.List;
import javax.swing.JTextArea;

/**
 * Manages intellisense functionality for the input text area in the AI Chat Panel.
 * This class handles detecting when to show command suggestions and inserting
 * selected commands into the text area.
 */
public class InputBoxIntellisense {

    private final JTextArea textArea;
    private final CommandIntellisenseProvider intellisenseProvider;
    private final IntellisensePopup intellisensePopup;

    /**
     * True while the system IME is composing a character (e.g. Pinyin on Windows).
     * When composing, key events related to candidate selection must NOT be
     * intercepted by the intellisense popup.
     */
    private volatile boolean imeComposing = false;

    /**
     * Creates a new InputBoxIntellisense for the specified text area.
     *
     * @param textArea The text area to add intellisense to
     */
    public InputBoxIntellisense(JTextArea textArea) {
        this.textArea = textArea;
        this.intellisenseProvider = new CommandIntellisenseProvider();
        this.intellisensePopup = new IntellisensePopup();

        setupKeyListeners();
        setupMouseListeners();
    }

    /**
     * Sets up key listeners for the text area to handle intellisense activation and navigation.
     */
    private void setupKeyListeners() {
        // -----------------------------------------------------------------------
        // Track IME composition state so that candidate-selection ENTER / TAB
        // presses are NOT stolen by the intellisense popup when the user is
        // composing Chinese (or other CJK) characters through Windows IME.
        // -----------------------------------------------------------------------
        textArea.addInputMethodListener(
            new InputMethodListener() {
                @Override
                public void inputMethodTextChanged(InputMethodEvent event) {
                    AttributedCharacterIterator text = event.getText();
                    if (text != null) {
                        int composingChars =
                            (text.getEndIndex() - text.getBeginIndex()) -
                            event.getCommittedCharacterCount();
                        imeComposing = composingChars > 0;
                    } else {
                        imeComposing = false;
                    }
                }

                @Override
                public void caretPositionChanged(InputMethodEvent event) {
                    // no-op
                }
            }
        );

        textArea.addKeyListener(
            new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    // When IME is composing do NOT intercept any key so that the
                    // native candidate-selection flow works correctly.
                    if (imeComposing) {
                        return;
                    }

                    // Handle Enter or Tab for intellisense selection
                    if (intellisensePopup.isVisible()) {
                        if (
                            e.getKeyCode() == KeyEvent.VK_ENTER ||
                            e.getKeyCode() == KeyEvent.VK_TAB
                        ) {
                            e.consume();
                            insertSelectedCommand();
                            return;
                        } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                            int curr = intellisensePopup.getSelectedIndex();
                            int next =
                                (curr + 1) %
                                intellisensePopup.getSuggestionCount();
                            intellisensePopup.setSelectedIndex(next);
                            e.consume();
                            return;
                        } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                            int curr = intellisensePopup.getSelectedIndex();
                            int prev =
                                (curr -
                                    1 +
                                    intellisensePopup.getSuggestionCount()) %
                                intellisensePopup.getSuggestionCount();
                            intellisensePopup.setSelectedIndex(prev);
                            e.consume();
                            return;
                        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                            intellisensePopup.hide();
                            e.consume();
                            return;
                        }
                    }
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    // Skip intellisense updates while IME is composing to avoid
                    // triggering @ command suggestions mid-composition.
                    if (imeComposing) {
                        return;
                    }
                    if (
                        e.isActionKey() ||
                        e.isControlDown() ||
                        e.isMetaDown() ||
                        e.isAltDown()
                    ) {
                        return;
                    }

                    updateIntellisense();
                }
            }
        );
    }

    /**
     * Sets up mouse listeners for the intellisense popup.
     */
    private void setupMouseListeners() {
        intellisensePopup.addSuggestionClickListener(
            new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 1) {
                        insertSelectedCommand();
                        intellisensePopup.hide();
                    }
                }
            }
        );
    }

    /**
     * Updates the intellisense popup based on the current text and caret position.
     */
    private void updateIntellisense() {
        int caret = textArea.getCaretPosition();
        String text = textArea.getText();
        int atIdx = text.lastIndexOf("@", caret - 1);

        if (
            atIdx >= 0 &&
            (atIdx == 0 || !Character.isLetterOrDigit(text.charAt(atIdx - 1)))
        ) {
            String prefix = text.substring(atIdx, caret);
            List<String> suggestions = intellisenseProvider.getSuggestions(
                prefix
            );

            if (!suggestions.isEmpty()) {
                Point pt;
                try {
                    Rectangle2D rect = textArea.modelToView2D(atIdx);
                    pt = new Point(
                        (int) rect.getX(),
                        (int) (rect.getY() + rect.getHeight())
                    );
                } catch (Exception ex) {
                    pt = new Point(0, textArea.getHeight());
                }
                intellisensePopup.show(textArea, pt.x, pt.y, suggestions);
            } else {
                intellisensePopup.hide();
            }
        } else {
            intellisensePopup.hide();
        }
    }

    /**
     * Inserts the currently selected command from the intellisense popup into the text area.
     */
    private void insertSelectedCommand() {
        String selected = intellisensePopup.getSelectedValue();
        if (selected != null) {
            try {
                int pos = textArea.getCaretPosition();
                String text = textArea.getText();
                int atIdx = text.lastIndexOf("@", pos - 1);
                if (atIdx >= 0) {
                    String before = text.substring(0, atIdx);
                    String after = text.substring(pos);
                    textArea.setText(before + selected + after);
                    textArea.setCaretPosition((before + selected).length());
                }
            } catch (Exception ex) {
                // fallback: do nothing
            }
        }
    }
}
