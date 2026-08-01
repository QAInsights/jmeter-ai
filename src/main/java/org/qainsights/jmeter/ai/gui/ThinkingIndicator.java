package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Animated "AI is thinking..." indicator for the chat transcript.
 * <p>
 * Replaces the previous approach of inserting a literal string and later
 * finding it with {@code lastIndexOf} - fragile when similar text appears in
 * the conversation. Instead, the indicator tracks its exact document offset
 * and removes precisely the range it inserted.
 * <p>
 * While active, a {@link Timer} cycles the trailing dots for a subtle
 * "working" animation. All document mutations happen on the EDT (the Swing
 * timer fires there), consistent with the rest of the transcript updates.
 */
public class ThinkingIndicator {

    private static final Logger log = LoggerFactory.getLogger(
        ThinkingIndicator.class
    );

    /** Base text shown while the AI is working (dots animate after it). */
    static final String BASE_TEXT = "AI is thinking";
    /** Timer interval for the dots animation. */
    static final int ANIMATION_DELAY_MS = 400;

    private int offset = -1;
    private int length = 0;
    private int dotCount = 0;
    private Timer timer;

    /** True while the indicator is present in the document. */
    public boolean isActive() {
        return offset >= 0;
    }

    /**
     * Appends the indicator to the document and starts the dots animation.
     * Calling {@code start} while already active is a no-op.
     *
     * @param doc the transcript document
     */
    public void start(StyledDocument doc) {
        if (isActive()) {
            return;
        }
        offset = doc.getLength();
        dotCount = 0;
        SimpleAttributeSet style = indicatorStyle();
        try {
            String text = BASE_TEXT + "\n";
            doc.insertString(offset, text, style);
            length = text.length();
        } catch (BadLocationException e) {
            log.error("Error adding thinking indicator", e);
            offset = -1;
            return;
        }
        timer = new Timer(ANIMATION_DELAY_MS, e -> animate(doc));
        timer.setRepeats(true);
        timer.start();
    }

    /**
     * Removes exactly the inserted range from the document and stops the
     * animation. Safe to call when the indicator is not active (no-op).
     *
     * @param doc the transcript document
     */
    public void stop(StyledDocument doc) {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (!isActive()) {
            return;
        }
        try {
            if (offset + length <= doc.getLength()) {
                doc.remove(offset, length);
            } else {
                log.warn(
                    "Thinking indicator range (offset {} + length {}) exceeds document length {} - skipping removal",
                    offset, length, doc.getLength()
                );
            }
        } catch (BadLocationException e) {
            log.error("Error removing thinking indicator", e);
        } finally {
            offset = -1;
            length = 0;
        }
    }

    private void animate(StyledDocument doc) {
        if (!isActive() || offset + length > doc.getLength()) {
            stop(doc);
            return;
        }
        dotCount = (dotCount + 1) % 4;
        String text = BASE_TEXT + ".".repeat(dotCount) + "\n";
        try {
            doc.remove(offset, length);
            doc.insertString(offset, text, indicatorStyle());
            length = text.length();
        } catch (BadLocationException e) {
            log.error("Error animating thinking indicator", e);
            stop(doc);
        }
    }

    private static SimpleAttributeSet indicatorStyle() {
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, ThemeColors.secondaryText());
        StyleConstants.setItalic(style, true);
        return style;
    }
}
