package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AnimatedGradientPainter}. */
class AnimatedGradientPainterTest {

    private static BufferedImage whiteImage(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.dispose();
        return image;
    }

    private static boolean anyNonWhitePixel(BufferedImage image) {
        int white = Color.WHITE.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != white) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void paintRotatingBorder_withPositiveAlpha_drawsSomething() {
        BufferedImage image = whiteImage(50);
        Graphics2D g2d = image.createGraphics();

        AnimatedGradientPainter.paintRotatingBorder(g2d, 50, 50, 90, 3.0f, 12, 2, 1f);
        g2d.dispose();

        assertTrue(anyNonWhitePixel(image));
    }

    @Test
    void paintRotatingBorder_withZeroAlpha_drawsNothing() {
        BufferedImage image = whiteImage(50);
        Graphics2D g2d = image.createGraphics();

        AnimatedGradientPainter.paintRotatingBorder(g2d, 50, 50, 90, 3.0f, 12, 2, 0f);
        g2d.dispose();

        assertFalse(anyNonWhitePixel(image));
    }

    @Test
    void paintRotatingBorder_withNegativeAlpha_drawsNothing() {
        BufferedImage image = whiteImage(50);
        Graphics2D g2d = image.createGraphics();

        AnimatedGradientPainter.paintRotatingBorder(g2d, 50, 50, 90, 3.0f, 12, 2, -1f);
        g2d.dispose();

        assertFalse(anyNonWhitePixel(image));
    }

    @Test
    void paintRotatingBorder_withZeroSize_doesNotThrow() {
        BufferedImage image = whiteImage(1);
        Graphics2D g2d = image.createGraphics();

        assertDoesNotThrow(() -> AnimatedGradientPainter.paintRotatingBorder(g2d, 0, 0, 90, 3.0f, 12, 2, 1f));
        g2d.dispose();
    }

    @Test
    void paintRotatingBorder_atEveryAngleAcrossAFullRotation_neverThrows() {
        BufferedImage image = whiteImage(50);
        Graphics2D g2d = image.createGraphics();

        for (int angle = 0; angle < 360; angle += 15) {
            int fixedAngle = angle;
            assertDoesNotThrow(() -> AnimatedGradientPainter.paintRotatingBorder(g2d, 50, 50, fixedAngle, 3.0f, 12, 2, 1f));
        }
        g2d.dispose();
    }

    @Test
    void paintRotatingBorder_withPartialAlpha_stillDrawsSomething() {
        BufferedImage image = whiteImage(50);
        Graphics2D g2d = image.createGraphics();

        AnimatedGradientPainter.paintRotatingBorder(g2d, 50, 50, 45, 3.0f, 12, 2, 0.5f);
        g2d.dispose();

        assertTrue(anyNonWhitePixel(image));
    }

    @Test
    void paintRotatingBorder_leavesComposite_restoredAfterPartialAlphaPaint() {
        BufferedImage image = whiteImage(50);
        Graphics2D g2d = image.createGraphics();
        java.awt.Composite original = g2d.getComposite();

        AnimatedGradientPainter.paintRotatingBorder(g2d, 50, 50, 45, 3.0f, 12, 2, 0.5f);

        assertEquals(original, g2d.getComposite());
        g2d.dispose();
    }
}
