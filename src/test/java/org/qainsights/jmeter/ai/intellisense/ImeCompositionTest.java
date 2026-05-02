package org.qainsights.jmeter.ai.intellisense;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.font.TextHitInfo;
import java.lang.reflect.Field;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import javax.swing.JTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for IME (Input Method Editor) composition tracking inside
 * {@link InputBoxIntellisense}.
 *
 * <h2>Background – why this matters on Windows 11</h2>
 * When a user types Chinese (or Japanese / Korean) text via a system IME:
 * <ol>
 *   <li>The user types Roman phonetics, e.g. "n i h a o" for 你好.</li>
 *   <li>The IME shows a candidate-character popup.</li>
 *   <li>The user presses ENTER to confirm the selected CJK character.</li>
 * </ol>
 * Without composition tracking, that ENTER key-press was intercepted by the
 * {@code KeyAdapter} in {@link InputBoxIntellisense} (and in AiChatPanel) and
 * treated as an intellisense-selection trigger or a send-message action,
 * breaking the entire CJK input flow.
 *
 * <h2>Fix</h2>
 * An {@code InputMethodListener} registered in
 * {@code InputBoxIntellisense.setupKeyListeners()} sets the private
 * {@code imeComposing} flag to {@code true} while characters are being composed
 * and {@code false} once all characters are committed.  The {@code KeyAdapter}
 * short-circuits immediately when {@code imeComposing} is {@code true}.
 *
 * <h2>Test strategy</h2>
 * We retrieve the registered {@link InputMethodListener} from the
 * {@link JTextArea} and fire synthetic {@link InputMethodEvent}s against it,
 * then verify the {@code imeComposing} field via reflection.
 */
class ImeCompositionTest {

    private JTextArea textArea;
    private InputBoxIntellisense intellisense;

    /** Reflective handle to the private {@code imeComposing} field. */
    private Field imeComposingField;

    /** The listener that was registered on the text area by InputBoxIntellisense. */
    private InputMethodListener imeListener;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        textArea = new JTextArea();
        intellisense = new InputBoxIntellisense(textArea);

        // Access the private imeComposing field
        imeComposingField = InputBoxIntellisense.class.getDeclaredField(
            "imeComposing"
        );
        imeComposingField.setAccessible(true);

        // Retrieve the InputMethodListener wired up during construction
        InputMethodListener[] listeners = textArea.getInputMethodListeners();
        assertTrue(
            listeners.length > 0,
            "InputBoxIntellisense must register an InputMethodListener on the text area"
        );
        imeListener = listeners[0];
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Read the current value of the private {@code imeComposing} field. */
    private boolean isComposing() throws Exception {
        return (boolean) imeComposingField.get(intellisense);
    }

    /**
     * Build an {@code INPUT_METHOD_TEXT_CHANGED} event.
     *
     * @param text      the candidate string currently in the IME buffer
     * @param committed number of leading characters that have already been committed
     */
    private InputMethodEvent buildTextChangedEvent(String text, int committed) {
        AttributedCharacterIterator iter = new AttributedString(
            text
        ).getIterator();
        // Use the 7-argument constructor (explicit 'when') to avoid calling
        // EventQueue.getMostRecentEventTime() which can be problematic in headless tests.
        return new InputMethodEvent(
            textArea,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            System.currentTimeMillis(),
            iter,
            committed,
            null, // caret TextHitInfo  – not needed for these tests
            null // visiblePosition    – not needed for these tests
        );
    }

    /** Build an {@code INPUT_METHOD_TEXT_CHANGED} event with {@code null} text. */
    private InputMethodEvent buildNullTextEvent() {
        return new InputMethodEvent(
            textArea,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            System.currentTimeMillis(),
            null, // null text = composition ended / cancelled
            0,
            null,
            null
        );
    }

    /** Build a {@code CARET_POSITION_CHANGED} event. */
    private InputMethodEvent buildCaretEvent() {
        // Java 17 exposes only the 4-arg constructor for CARET_POSITION_CHANGED:
        // InputMethodEvent(Component source, int id, TextHitInfo caret, TextHitInfo visiblePosition)
        // Explicit TextHitInfo casts are required to resolve the overload unambiguously.
        return new InputMethodEvent(
            textArea,
            InputMethodEvent.CARET_POSITION_CHANGED,
            (TextHitInfo) null, // caret
            (TextHitInfo) null // visiblePosition
        );
    }

    // =========================================================================
    // 1. Initial state
    // =========================================================================

    @Test
    void imeComposing_initiallyFalse() throws Exception {
        assertFalse(
            isComposing(),
            "imeComposing must be false before any IME event is received"
        );
    }

    // =========================================================================
    // 2. Composing (candidate popup open – ENTER should NOT send the message)
    // =========================================================================

    @Test
    void imeComposing_trueWhenCandidatePopupIsOpen() throws Exception {
        // "你好" typed via Pinyin, nothing committed yet
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你好", 0));

        assertTrue(
            isComposing(),
            "imeComposing must be true while the IME candidate popup is open"
        );
    }

    @Test
    void imeComposing_trueForSingleRomanComposingKey() throws Exception {
        // User presses 'n' – one Roman key, nothing committed yet
        imeListener.inputMethodTextChanged(buildTextChangedEvent("n", 0));

        assertTrue(
            isComposing(),
            "imeComposing must be true when any character is being composed"
        );
    }

    @Test
    void imeComposing_trueWhenPartiallyCommitted() throws Exception {
        // 3-char IME buffer; only the first character is committed
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你好吗", 1));

        assertTrue(
            isComposing(),
            "imeComposing must be true when some characters remain uncommitted"
        );
    }

    // =========================================================================
    // 3. Committed state (ENTER should now send the message)
    // =========================================================================

    @Test
    void imeComposing_falseAfterSingleCharCommit() throws Exception {
        // Start composing
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你", 0));
        assertTrue(isComposing(), "pre-condition: should be composing");

        // Confirm the character (all 1 char committed)
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你", 1));

        assertFalse(
            isComposing(),
            "imeComposing must be false after the character is committed"
        );
    }

    @Test
    void imeComposing_falseAfterMultiCharCommit() throws Exception {
        // Compose two characters
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你好", 0));
        assertTrue(isComposing(), "pre-condition: should be composing");

        // Both characters confirmed at once
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你好", 2));

        assertFalse(
            isComposing(),
            "imeComposing must be false after all characters are committed"
        );
    }

    @Test
    void imeComposing_falseWhenNullTextReceived() throws Exception {
        // Windows IMEs sometimes send a null-text event when composition is
        // cancelled (e.g., user presses Escape in the candidate popup).
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你", 0));
        assertTrue(isComposing(), "pre-condition: should be composing");

        imeListener.inputMethodTextChanged(buildNullTextEvent());

        assertFalse(
            isComposing(),
            "imeComposing must be false when a null-text event is received " +
                "(composition cancelled or ended)"
        );
    }

    // =========================================================================
    // 4. Rapid state transitions  (realistic Pinyin typing session)
    // =========================================================================

    /**
     * Simulates typing "你好" via Pinyin:
     * 'n' → 'ni' → commit '你' → 'h' → 'hao' → commit '好'
     */
    @Test
    void imeComposing_correctlyTransitionsBetweenStates() throws Exception {
        assertFalse(isComposing(), "starts false");

        imeListener.inputMethodTextChanged(buildTextChangedEvent("n", 0));
        assertTrue(isComposing(), "'n' typed → composing");

        imeListener.inputMethodTextChanged(buildTextChangedEvent("ni", 0));
        assertTrue(isComposing(), "'ni' typed → still composing");

        imeListener.inputMethodTextChanged(buildTextChangedEvent("你", 1));
        assertFalse(isComposing(), "'你' committed → not composing");

        imeListener.inputMethodTextChanged(buildTextChangedEvent("h", 0));
        assertTrue(isComposing(), "'h' typed → composing again");

        imeListener.inputMethodTextChanged(buildTextChangedEvent("hao", 0));
        assertTrue(isComposing(), "'hao' typed → still composing");

        imeListener.inputMethodTextChanged(buildTextChangedEvent("好", 1));
        assertFalse(isComposing(), "'好' committed → not composing");
    }

    /**
     * Ensures that repeatedly switching between composing and committed states
     * works without any leftover state.
     */
    @Test
    void imeComposing_handlesMultipleCharactersSequentially() throws Exception {
        String[] characters = { "中", "文", "测", "试" };

        for (String ch : characters) {
            imeListener.inputMethodTextChanged(buildTextChangedEvent(ch, 0));
            assertTrue(isComposing(), "should be composing for char: " + ch);

            imeListener.inputMethodTextChanged(buildTextChangedEvent(ch, 1));
            assertFalse(
                isComposing(),
                "should not be composing after committing: " + ch
            );
        }
    }

    // =========================================================================
    // 5. caretPositionChanged must NOT change composition state
    // =========================================================================

    /**
     * During composition the caret can move (e.g., when the user navigates the
     * candidate list). {@code caretPositionChanged} must be a no-op with respect
     * to the {@code imeComposing} flag.
     */
    @Test
    void caretPositionChanged_doesNotAlterComposingState_whenComposing()
        throws Exception {
        imeListener.inputMethodTextChanged(buildTextChangedEvent("你", 0));
        assertTrue(isComposing(), "pre-condition: composing");

        imeListener.caretPositionChanged(buildCaretEvent());

        assertTrue(
            isComposing(),
            "caretPositionChanged must not clear imeComposing while composing"
        );
    }

    @Test
    void caretPositionChanged_doesNotAlterComposingState_whenNotComposing()
        throws Exception {
        assertFalse(isComposing(), "pre-condition: not composing");

        imeListener.caretPositionChanged(buildCaretEvent());

        assertFalse(
            isComposing(),
            "caretPositionChanged must not set imeComposing when not composing"
        );
    }

    // =========================================================================
    // 6. Verify that InputBoxIntellisense registers exactly one IME listener
    // =========================================================================

    @Test
    void inputBoxIntellisense_registersExactlyOneInputMethodListener() {
        InputMethodListener[] listeners = textArea.getInputMethodListeners();
        assertEquals(
            1,
            listeners.length,
            "InputBoxIntellisense should register exactly one InputMethodListener"
        );
    }
}
