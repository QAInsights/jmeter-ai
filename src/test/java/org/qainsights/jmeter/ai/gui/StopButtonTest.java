package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StopButton}: configuration, the stop action, and
 * painting in normal and hover states.
 */
class StopButtonTest {

    @Test
    void configuredAsCircleButton() {
        StopButton button = new StopButton(() -> {});
        assertEquals("Stop the current response", button.getToolTipText());
        assertFalse(button.isContentAreaFilled(), "the disc must be painted by paintComponent");
        assertFalse(button.isBorderPainted());
        assertEquals(32, button.getPreferredSize().width);
        assertEquals(32, button.getPreferredSize().height);
    }

    @Test
    void clickRunsTheStopAction() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        StopButton button = new StopButton(() -> stopped.set(true));
        button.doClick();
        assertTrue(stopped.get(), "clicking must run the stop action");
    }

    @Test
    void paintsInNormalAndHoverStates() {
        StopButton button = new StopButton(() -> {});
        button.setSize(32, 32);

        BufferedImage normal = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        button.paint(normal.getGraphics());
        assertTrue(hasVisiblePixels(normal), "normal state must paint");

        button.getModel().setRollover(true);
        BufferedImage hover = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        button.paint(hover.getGraphics());
        assertTrue(hasVisiblePixels(hover), "hover state must paint");
        button.getModel().setRollover(false);
    }

    private static boolean hasVisiblePixels(BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
