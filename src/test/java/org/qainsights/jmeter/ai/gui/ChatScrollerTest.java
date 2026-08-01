package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatScrollerTest {

    /**
     * Builds a scroll pane whose vertical scrollbar model is driven directly.
     * Layout is unreliable for unparented components in test JVMs (extent can
     * be 0), so the range properties are set explicitly for determinism.
     */
    private JScrollPane scrollPaneWithRange(int value, int extent, int max) {
        JScrollPane pane = new JScrollPane(new JTextArea());
        pane.getVerticalScrollBar()
            .getModel()
            .setRangeProperties(value, extent, 0, max, false);
        return pane;
    }

    @Test
    void pinnedWhenAtBottom() {
        JScrollPane pane = scrollPaneWithRange(4900, 100, 5000);
        assertTrue(ChatScroller.isPinnedToBottom(pane));
    }

    @Test
    void pinnedWithinToleranceOfBottom() {
        int tolerance = ChatScroller.BOTTOM_TOLERANCE_PX;
        JScrollPane pane = scrollPaneWithRange(5000 - 100 - tolerance, 100, 5000);
        assertTrue(ChatScroller.isPinnedToBottom(pane));
    }

    @Test
    void notPinnedWhenScrolledUp() {
        JScrollPane pane = scrollPaneWithRange(0, 100, 5000);
        assertFalse(ChatScroller.isPinnedToBottom(pane));
    }

    @Test
    void nullScrollPaneCountsAsPinned() {
        assertTrue(ChatScroller.isPinnedToBottom(null));
    }

    @Test
    void scrollToBottomOnlyWhenPinned() {
        // Mock the pane: a real pane wires viewport listeners that fight the
        // value change in unparented components, hiding the model behavior
        // under test. The detached scrollbar keeps real clamping semantics.
        javax.swing.JScrollBar bar = new javax.swing.JScrollBar();
        bar.getModel().setRangeProperties(10, 100, 0, 5000, false);
        JScrollPane pane = org.mockito.Mockito.mock(JScrollPane.class);
        org.mockito.Mockito.when(pane.getVerticalScrollBar()).thenReturn(bar);

        ChatScroller.scrollToBottomIfPinned(pane, false);
        assertEquals(10, bar.getValue());

        ChatScroller.scrollToBottomIfPinned(pane, true);
        // setValue(max) clamps to max - extent
        assertEquals(5000 - 100, bar.getValue());
    }

    @Test
    void scrollPaneOfReturnsNullWithoutAncestor() {
        assertNull(ChatScroller.scrollPaneOf(new JTextArea()));
    }
}
