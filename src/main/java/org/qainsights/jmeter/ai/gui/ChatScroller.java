package org.qainsights.jmeter.ai.gui;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Standard chat-style auto-scroll behavior for the transcript.
 * <p>
 * The view only follows new content when the user is already at (or very
 * near) the bottom. If the user has scrolled up to read earlier messages,
 * incoming streamed tokens never yank the scrollbar away from them.
 */
public final class ChatScroller {

    /**
     * Distance from the bottom (in pixels) within which the view is still
     * considered "pinned". Roughly a couple of text lines.
     */
    static final int BOTTOM_TOLERANCE_PX = 48;

    private ChatScroller() {
    }

    /**
     * Finds the scroll pane containing the given component (typically the
     * chat transcript).
     *
     * @param component a component inside the scroll pane
     * @return the ancestor scroll pane, or null if none
     */
    public static JScrollPane scrollPaneOf(JComponent component) {
        return (JScrollPane) SwingUtilities.getAncestorOfClass(
            JScrollPane.class,
            component
        );
    }

    /**
     * Returns true when the scroll pane's view is at (or within tolerance of)
     * the bottom - i.e. new content should be auto-scrolled into view.
     * A missing scroll pane counts as pinned so content is always revealed.
     */
    public static boolean isPinnedToBottom(JScrollPane scrollPane) {
        if (scrollPane == null) {
            return true;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar == null) {
            return true;
        }
        int currentBottom = bar.getValue() + bar.getModel().getExtent();
        return bar.getMaximum() - currentBottom <= BOTTOM_TOLERANCE_PX;
    }

    /**
     * Scrolls to the bottom if {@code wasPinned} is true. Capture the pinned
     * state <em>before</em> inserting new content, then call this afterwards.
     */
    public static void scrollToBottomIfPinned(
        JScrollPane scrollPane,
        boolean wasPinned
    ) {
        if (scrollPane == null || !wasPinned) {
            return;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar != null) {
            bar.setValue(bar.getMaximum());
        }
    }
}
