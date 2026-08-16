package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DonateButton}: configuration (icon, tooltip, regular
 * button chrome) and that painting - including the rotating gradient outline -
 * works in normal and hover states.
 */
class DonateButtonTest {

    @Test
    void configuredAsARegularButtonWithACoffeeIcon() {
        DonateButton button = new DonateButton();
        assertEquals("Donate", button.getText());
        assertNotNull(button.getIcon(), "coffee icon must be set");
        assertTrue(button.getToolTipText().contains("Support this project"));
        assertTrue(button.isContentAreaFilled(), "background must be the native button chrome");
        assertTrue(button.isBorderPainted(), "border must be the native button chrome");
    }

    @Test
    void paintsInNormalAndHoverStates() {
        DonateButton button = new DonateButton();
        button.setSize(120, 30);
        BufferedImage image = new BufferedImage(120, 30, BufferedImage.TYPE_INT_ARGB);

        assertDoesNotThrow(() -> button.paint(image.getGraphics()));
        assertTrue(hasVisiblePixels(image), "normal state must paint, including the gradient outline");

        button.getModel().setRollover(true);
        BufferedImage hoverImage = new BufferedImage(120, 30, BufferedImage.TYPE_INT_ARGB);
        assertDoesNotThrow(() -> button.paint(hoverImage.getGraphics()));
        assertTrue(hasVisiblePixels(hoverImage), "hover state must paint");
        button.getModel().setRollover(false);
    }

    @Test
    void iconRendersVisiblePixels() {
        DonateButton button = new DonateButton();
        javax.swing.Icon icon = button.getIcon();
        BufferedImage image = new BufferedImage(
                icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        icon.paintIcon(null, image.getGraphics(), 0, 0);
        assertTrue(hasVisiblePixels(image), "coffee icon must paint");
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
