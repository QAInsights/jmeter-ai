package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import javax.swing.Icon;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StarIcons}: size contracts and that both star
 * variants actually paint visible pixels (filled paints more than outline).
 */
class StarIconsTest {

    private static int nonTransparentPixels(Icon icon) {
        BufferedImage image = new BufferedImage(
                icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        icon.paintIcon(null, image.getGraphics(), 0, 0);
        int count = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void outlineHasSizeAndPaints() {
        Icon icon = StarIcons.outline(14);
        assertEquals(14, icon.getIconWidth());
        assertEquals(14, icon.getIconHeight());
        assertTrue(nonTransparentPixels(icon) > 0, "outline star must paint visible pixels");
    }

    @Test
    void filledHasSizeAndPaints() {
        Icon icon = StarIcons.filled(14);
        assertEquals(14, icon.getIconWidth());
        assertEquals(14, icon.getIconHeight());
        assertTrue(nonTransparentPixels(icon) > 0, "filled star must paint visible pixels");
    }

    @Test
    void filledCoversTheCenterButOutlineDoesNot() {
        int size = 16;
        BufferedImage filledImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        StarIcons.filled(size).paintIcon(null, filledImage.getGraphics(), 0, 0);
        BufferedImage outlineImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        StarIcons.outline(size).paintIcon(null, outlineImage.getGraphics(), 0, 0);

        int center = size / 2;
        assertTrue((filledImage.getRGB(center, center) >>> 24) != 0,
                "the filled star paints its center pixel");
        assertEquals(0, outlineImage.getRGB(center, center) >>> 24,
                "the outline star leaves its center pixel transparent");
    }

    @Test
    void starPathIsClosedAndTenPointed() {
        java.awt.geom.GeneralPath star = StarIcons.starPath(0, 0, 14);
        assertNotNull(star.getCurrentPoint());
        assertTrue(star.getBounds2D().getWidth() > 0);
        assertTrue(star.getBounds2D().getHeight() > 0);
    }
}
