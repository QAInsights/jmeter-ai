package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import javax.swing.Icon;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChevronIcons}: size contract and that painting
 * actually produces visible (non-transparent) pixels.
 */
class ChevronIconsTest {

    @Test
    void downChevronHasSizeAndPaints() {
        Icon icon = ChevronIcons.down(10);
        assertEquals(10, icon.getIconWidth());
        assertEquals(10, icon.getIconHeight());

        BufferedImage image = new BufferedImage(
                icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        icon.paintIcon(null, image.getGraphics(), 0, 0);
        int painted = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    painted++;
                }
            }
        }
        assertTrue(painted > 0, "chevron must paint visible pixels");
    }
}
