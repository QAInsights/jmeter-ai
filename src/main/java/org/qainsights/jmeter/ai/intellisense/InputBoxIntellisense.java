package org.qainsights.jmeter.ai.intellisense;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.AttributedCharacterIterator;
import java.util.List;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * Manages intellisense functionality for the input text area in the AI Chat Panel.
 * This class handles detecting when to show command suggestions and inserting
 * selected commands into the text area.
 */
public class InputBoxIntellisense {

    /** Command that switches the popup into prompt-picker mode once followed by a space. */
    static final String PROMPTS_COMMAND = "@prompts";

    private final JTextArea textArea;
    private final CommandIntellisenseProvider intellisenseProvider;
    private final IntellisensePopup intellisensePopup;

    /** Enables the {@code @prompts <query>} picker mode when set. */
    private org.qainsights.jmeter.ai.service.prompts.PromptLibrary promptLibrary;

    /**
     * Prompts matching the last prompt-mode query; non-empty means the popup
     * is currently showing prompts (and selection inserts a body, not a name).
     */
    private List<org.qainsights.jmeter.ai.service.prompts.PromptLibrary.Prompt> promptMatches =
            List.of();

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

    /** Sets the prompt library backing the {@code @prompts} picker mode. */
    public void setPromptLibrary(
            org.qainsights.jmeter.ai.service.prompts.PromptLibrary promptLibrary) {
        this.promptLibrary = promptLibrary;
        if (promptLibrary != null) {
            // Keeps an open picker in sync with saves/deletes from elsewhere
            // (e.g. a MessageCard "Save prompt" while the picker is up).
            promptLibrary.addChangeListener(
                () -> javax.swing.SwingUtilities.invokeLater(this::refreshPromptPicker)
            );
        }
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

        // -----------------------------------------------------------------------
        // Picker keys go through a global KeyEventDispatcher, not the text
        // area's key listener: the suggestion popup opens in its own
        // heavy-weight window above the input, so when it appears focus can
        // transiently (or in the JMeter main window, persistently) leave the
        // text area. While the picker is visible its keys - arrows, Enter,
        // Tab, Esc, and the prompt-manage keys - must work regardless of
        // which component holds focus. Returning true consumes the event, so
        // the text area never double-handles them.
        // -----------------------------------------------------------------------
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (imeComposing
                    || e.getID() != KeyEvent.KEY_PRESSED
                    || !intellisensePopup.isVisible()) {
                return false;
            }
            switch (e.getKeyCode()) {
                case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                    insertSelectedCommand();
                    // re-evaluate: "@prompts " drops straight into the
                    // picker, anything else leaves no @ token and hides it
                    updateIntellisense();
                    return true;
                }
                case KeyEvent.VK_DOWN -> {
                    moveSelection(1);
                    return true;
                }
                case KeyEvent.VK_UP -> {
                    moveSelection(-1);
                    return true;
                }
                case KeyEvent.VK_ESCAPE -> {
                    intellisensePopup.hide();
                    return true;
                }
                case KeyEvent.VK_DELETE -> {
                    if (!promptMatches.isEmpty()) {
                        deleteSelectedPrompt();
                        return true;
                    }
                    return false;
                }
                case KeyEvent.VK_F2 -> {
                    if (!promptMatches.isEmpty()) {
                        editSelectedPrompt();
                        return true;
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        });

        textArea.addKeyListener(
            new KeyAdapter() {
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
     * If the @ token at the caret is "@prompts &lt;query&gt;", returns the query
     * (possibly empty); otherwise null. Package-private for tests.
     */
    String promptQuery(String text, int caret) {
        if (promptLibrary == null || caret <= 0 || caret > text.length()) {
            return null;
        }
        int atIdx = text.lastIndexOf("@", caret - 1);
        if (atIdx < 0 || (atIdx > 0 && Character.isLetterOrDigit(text.charAt(atIdx - 1)))) {
            return null;
        }
        String token = text.substring(atIdx, caret);
        String trigger = PROMPTS_COMMAND + " ";
        return token.startsWith(trigger) ? token.substring(trigger.length()) : null;
    }

    /**
     * Maps a selected suggestion to the text that replaces the @ token: the
     * prompt body in prompt mode, "@prompts " (with trailing space, dropping
     * straight into the picker) for the prompts command, otherwise the
     * suggestion itself. Package-private for tests.
     */
    String insertionFor(String selected) {
        if (selected == null) {
            return null;
        }
        if (!promptMatches.isEmpty()) {
            return promptMatches.stream()
                .filter(p -> p.name().equals(selected))
                .findFirst()
                .map(org.qainsights.jmeter.ai.service.prompts.PromptLibrary.Prompt::body)
                .orElse(selected);
        }
        return PROMPTS_COMMAND.equals(selected) ? PROMPTS_COMMAND + " " : selected;
    }

    /**
     * Moves the popup selection by delta with wrap-around. Package-private
     * for tests.
     */
    void moveSelection(int delta) {
        int count = intellisensePopup.getSuggestionCount();
        if (count == 0) {
            return;
        }
        int curr = intellisensePopup.getSelectedIndex();
        intellisensePopup.setSelectedIndex(Math.floorMod(curr + delta, count));
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
            String query = promptQuery(text, caret);
            List<String> suggestions;
            if (query != null) {
                suggestions = promptSuggestions(query);
            } else {
                exitPromptMode();
                String prefix = text.substring(atIdx, caret);
                suggestions = intellisenseProvider.getSuggestions(prefix);
            }

            if (!suggestions.isEmpty()) {
                showAt(suggestions);
            } else {
                intellisensePopup.hide();
            }
        } else {
            exitPromptMode();
            intellisensePopup.hide();
        }
    }

    /**
     * Shows the popup docked above the chat input, full-width like the model
     * picker - independent of where the @ token sits in the text. The dock
     * point is the input's visible top edge (the viewport's) converted into
     * the text area's coordinate space: the text area stays the popup
     * invoker so it keeps keyboard focus (a viewport invoker would swallow
     * the arrow keys and further typing).
     */
    private void showAt(List<String> suggestions) {
        Point topLeft = SwingUtilities.convertPoint(anchorFor(textArea), 0, 0, textArea);
        intellisensePopup.showAbove(textArea, topLeft.x, topLeft.y, suggestions);
    }

    /**
     * The component the popup docks to: the input's viewport when the text
     * area is scrolled (its top edge is always visible), else the text area
     * itself. Package-private for tests.
     */
    static Component anchorFor(JTextArea textArea) {
        Container viewport = SwingUtilities.getAncestorOfClass(JViewport.class, textArea);
        return viewport != null ? viewport : textArea;
    }

    /** The suggestion popup (package-private for tests). */
    IntellisensePopup popup() {
        return intellisensePopup;
    }

    /** The prompt currently selected in prompt mode, or null. Package-private for tests. */
    org.qainsights.jmeter.ai.service.prompts.PromptLibrary.Prompt selectedPrompt() {
        if (promptMatches.isEmpty()) {
            return null;
        }
        String selected = intellisensePopup.getSelectedValue();
        return promptMatches.stream()
            .filter(p -> p.name().equals(selected))
            .findFirst()
            .orElse(null);
    }

    /**
     * Re-runs the current prompt query after a library change (picker manage
     * actions, MessageCard saves), keeping the open picker in sync.
     */
    private void refreshPromptPicker() {
        if (promptMatches.isEmpty() || !intellisensePopup.isVisible()) {
            return;
        }
        String text = textArea.getText();
        int caret = textArea.getCaretPosition();
        String query = promptQuery(text, caret);
        if (query == null) {
            exitPromptMode();
            intellisensePopup.hide();
            return;
        }
        List<String> names = promptSuggestions(query);
        if (names.isEmpty()) {
            exitPromptMode();
            intellisensePopup.hide();
        } else {
            showAt(names);
        }
    }

    /** Del in prompt mode: deletes the selected user prompt after a confirm. */
    private void deleteSelectedPrompt() {
        org.qainsights.jmeter.ai.service.prompts.PromptLibrary.Prompt selected = selectedPrompt();
        if (selected == null) {
            return;
        }
        if (selected.builtin()) {
            javax.swing.JOptionPane.showMessageDialog(textArea,
                "Built-in prompts can't be deleted.",
                "Prompt library", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (javax.swing.JOptionPane.showConfirmDialog(textArea,
                "Delete prompt \"" + selected.name() + "\"?",
                "Delete prompt", javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE) == javax.swing.JOptionPane.YES_OPTION) {
            promptLibrary.delete(selected.name());
        }
    }

    /** F2 in prompt mode: edit/rename a user prompt, or "save as copy" for a built-in. */
    private void editSelectedPrompt() {
        org.qainsights.jmeter.ai.service.prompts.PromptLibrary.Prompt selected = selectedPrompt();
        if (selected == null) {
            return;
        }
        java.awt.Window owner = javax.swing.SwingUtilities.getWindowAncestor(textArea);
        org.qainsights.jmeter.ai.gui.PromptEditDialog dialog = selected.builtin()
            ? org.qainsights.jmeter.ai.gui.PromptEditDialog.forCopy(owner, promptLibrary, selected)
            : org.qainsights.jmeter.ai.gui.PromptEditDialog.forEdit(owner, promptLibrary, selected);
        dialog.setVisible(true);
        // Library change listeners refresh the picker; nothing else to do.
    }

    /**
     * Enters prompt-picker mode for the query: caches the matches (selection
     * inserts the body) and switches the popup's descriptions to prompt
     * previews. Returns the names to show. Package-private for tests.
     */
    List<String> promptSuggestions(String query) {
        promptMatches = promptLibrary.filter(query);
        intellisensePopup.setDescriptionLookup(name ->
            promptLibrary.find(name)
                .map(org.qainsights.jmeter.ai.service.prompts.PromptLibrary.Prompt::preview)
                .orElse(""));
        return promptMatches.stream()
            .map(org.qainsights.jmeter.ai.service.prompts.PromptLibrary.Prompt::name)
            .toList();
    }

    /** Leaves prompt-picker mode, restoring command descriptions in the popup. */
    private void exitPromptMode() {
        promptMatches = List.of();
        intellisensePopup.setDescriptionLookup(null);
    }

    /**
     * Inserts the currently selected command from the intellisense popup into the text area.
     */
    private void insertSelectedCommand() {
        String selected = insertionFor(intellisensePopup.getSelectedValue());
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
                    exitPromptMode();
                }
            } catch (Exception ex) {
                // fallback: do nothing
            }
        }
    }
}
