package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class GeminiBorderPanelTest {

    @Test
    @org.junit.jupiter.api.condition.DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
    void testConstructorAndStates() {
        GeminiBorderPanel panel = new GeminiBorderPanel();

        assertNotNull(panel);
        assertFalse(panel.isThinking());

        // Enable thinking mode
        panel.setThinking(true);
        assertTrue(panel.isThinking());

        // Disable thinking mode
        panel.setThinking(false);
        assertFalse(panel.isThinking());
    }

    @Test
    void testPaintComponent() {
        GeminiBorderPanel panel = new GeminiBorderPanel();
        panel.setSize(200, 100);

        // Create a graphics context to test painting
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Paint static state
        assertDoesNotThrow(() -> panel.paint(g2d));

        // Paint thinking state
        panel.setThinking(true);
        assertDoesNotThrow(() -> panel.paint(g2d));

        g2d.dispose();
    }
}
