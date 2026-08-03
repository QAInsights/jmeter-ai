package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import javax.swing.Icon;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttachIcons}: size contracts and that painting
 * actually produces visible (non-transparent) pixels.
 */
class AttachIconsTest {

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
    void paperclipHasSizeAndPaints() {
        Icon icon = AttachIcons.paperclip(16);
        assertEquals(16, icon.getIconWidth());
        assertEquals(16, icon.getIconHeight());
        assertTrue(nonTransparentPixels(icon) > 0, "paperclip must paint visible pixels");
    }

    @Test
    void documentHasSizeAndPaints() {
        Icon icon = AttachIcons.document(12);
        assertEquals(12, icon.getIconWidth());
        assertEquals(12, icon.getIconHeight());
        assertTrue(nonTransparentPixels(icon) > 0, "document must paint visible pixels");
    }
}
