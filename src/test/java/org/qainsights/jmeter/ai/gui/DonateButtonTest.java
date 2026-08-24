package org.qainsights.jmeter.ai.gui;

import java.awt.Insets;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DonateButtonTest {

    @Test
    void usesRecordStyleNativeChromeWithoutAnIcon() {
        DonateButton button = new DonateButton();
        assertEquals("Donate", button.getText());
        assertNull(button.getIcon());
        assertEquals(new Insets(2, 6, 2, 6), button.getMargin());
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_TEXT_BUTTON_WIDTH,
                button.getPreferredSize().width);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().height);
        assertTrue(button.isContentAreaFilled());
        assertTrue(button.isBorderPainted());
        assertTrue(button.getToolTipText().contains("Support this project"));
    }

    @Test
    void animationFollowsComponentLifecycle() {
        DonateButton button = new DonateButton();
        assertFalse(button.isAnimationRunning());

        button.addNotify();
        try {
            assertTrue(button.isAnimationRunning());
        } finally {
            button.removeNotify();
        }
        assertFalse(button.isAnimationRunning());
    }

    @Test
    void animationAdvancesAndPaintsWithSharedGradient() {
        DonateButton button = new DonateButton();
        button.setSize(100, 28);
        int before = button.rotationAngle();
        button.advanceAnimation();
        assertEquals((before + 2) % 360, button.rotationAngle());

        BufferedImage image = new BufferedImage(100, 28, BufferedImage.TYPE_INT_ARGB);
        assertDoesNotThrow(() -> button.paint(image.getGraphics()));
        assertTrue(hasVisiblePixels(image));
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
