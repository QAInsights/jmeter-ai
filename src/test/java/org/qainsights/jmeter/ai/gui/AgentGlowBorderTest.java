package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;

import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AgentGlowBorder}. */
class AgentGlowBorderTest {

    private static BufferedImage filledImage(int size, Color fill) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(fill);
        g.fillRect(0, 0, size, size);
        g.dispose();
        return image;
    }

    private static boolean anyPixelChanged(BufferedImage before, BufferedImage after) {
        for (int y = 0; y < before.getHeight(); y++) {
            for (int x = 0; x < before.getWidth(); x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void getBorderInsets_isTheSameRegardlessOfPaintsFlag() {
        AgentGlowBorder painting = new AgentGlowBorder(() -> 0, () -> 1f, true);
        AgentGlowBorder quiet = new AgentGlowBorder(() -> 0, () -> 1f, false);

        Insets a = painting.getBorderInsets(new JLabel());
        Insets b = quiet.getBorderInsets(new JLabel());

        assertEquals(a, b);
        assertTrue(a.top > 0 && a.left > 0);
    }

    @Test
    void isBorderOpaque_isFalse() {
        assertFalse(new AgentGlowBorder(() -> 0, () -> 1f, true).isBorderOpaque());
    }

    @Test
    void paintBorder_whenPaintsIsFalse_doesNotModifyPixels() {
        AgentGlowBorder border = new AgentGlowBorder(() -> 45, () -> 1f, false);
        BufferedImage before = filledImage(40, Color.WHITE);
        BufferedImage after = filledImage(40, Color.WHITE);
        Graphics2D g = after.createGraphics();

        assertDoesNotThrow(() -> border.paintBorder(new JLabel(), g, 0, 0, 40, 40));
        g.dispose();

        assertFalse(anyPixelChanged(before, after));
    }

    @Test
    void paintBorder_whenPaintsIsTrueAndAlphaPositive_modifiesPixels() {
        AgentGlowBorder border = new AgentGlowBorder(() -> 45, () -> 1f, true);
        BufferedImage before = filledImage(40, Color.WHITE);
        BufferedImage after = filledImage(40, Color.WHITE);
        Graphics2D g = after.createGraphics();

        assertDoesNotThrow(() -> border.paintBorder(new JLabel(), g, 0, 0, 40, 40));
        g.dispose();

        assertTrue(anyPixelChanged(before, after));
    }

    @Test
    void paintBorder_withZeroSize_doesNotThrow() {
        AgentGlowBorder border = new AgentGlowBorder(() -> 0, () -> 1f, true);
        BufferedImage image = filledImage(1, Color.WHITE);
        Graphics2D g = image.createGraphics();

        assertDoesNotThrow(() -> border.paintBorder(new JLabel(), g, 0, 0, 0, 0));
        g.dispose();
    }
}
