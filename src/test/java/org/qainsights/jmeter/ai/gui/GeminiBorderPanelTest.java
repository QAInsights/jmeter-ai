package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.UIManager;

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

        panel.setFocused(true);
        assertTrue(panel.isFocused());
        panel.setFocused(false);
        assertFalse(panel.isFocused());
    }

    @Test
    void animationStopsWhenComposerLeavesComponentHierarchy() {
        GeminiBorderPanel panel = new GeminiBorderPanel();
        panel.setThinking(true);
        assertFalse(panel.isAnimationRunning());

        panel.addNotify();
        try {
            assertTrue(panel.isAnimationRunning());
        } finally {
            panel.removeNotify();
        }
        assertFalse(panel.isAnimationRunning());
    }

    @Test
    void testApplyThemeBackgroundFollowsThemeChange() {
        Color originalPanel = UIManager.getColor("Panel.background");
        Color originalCanvas = UIManager.getColor("TextPane.background");
        try {
            UIManager.put("Panel.background", new Color(30, 30, 30));
            UIManager.put("TextPane.background", new Color(25, 25, 25));
            GeminiBorderPanel panel = new GeminiBorderPanel();
            assertEquals(org.qainsights.jmeter.ai.gui.theme.ThemeColors.elevatedSurface(),
                    panel.getBackground());

            // Simulate a theme switch to a light background
            UIManager.put("Panel.background", new Color(245, 245, 245));
            UIManager.put("TextPane.background", Color.WHITE);
            panel.applyThemeBackground();
            assertEquals(org.qainsights.jmeter.ai.gui.theme.ThemeColors.elevatedSurface(),
                    panel.getBackground());
        } finally {
            UIManager.put("Panel.background", originalPanel);
            UIManager.put("TextPane.background", originalCanvas);
        }
    }

    @Test
    void testApplyThemeBackgroundFallsBackToWhite() {
        Color originalPanel = UIManager.getColor("Panel.background");
        Color originalCanvas = UIManager.getColor("TextPane.background");
        try {
            UIManager.put("Panel.background", null);
            UIManager.put("TextPane.background", null);
            GeminiBorderPanel panel = new GeminiBorderPanel();
            assertEquals(Color.WHITE, panel.getBackground());
        } finally {
            UIManager.put("Panel.background", originalPanel);
            UIManager.put("TextPane.background", originalCanvas);
        }
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
