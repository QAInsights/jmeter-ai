package org.qainsights.jmeter.ai.gui;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietButtonTest {

    @Test
    void iconButtonUsesConsistentAccessibleTarget() {
        QuietButton button = new QuietButton("").iconOnly();
        assertEquals(32, button.getPreferredSize().width);
        assertEquals(32, button.getPreferredSize().height);
        assertFalse(button.isContentAreaFilled());
        assertFalse(button.isBorderPainted());

        QuietButton headerButton = new QuietButton("").iconOnly(
                org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                headerButton.getPreferredSize().width);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                headerButton.getPreferredSize().height);
    }

    @Test
    void variantsRetainTheirVisualRole() {
        assertEquals(QuietButton.Kind.GHOST, new QuietButton("Ghost").kind());
        assertEquals(QuietButton.Kind.OUTLINED,
                new QuietButton("Outlined", QuietButton.Kind.OUTLINED).kind());
        assertEquals(QuietButton.Kind.TONAL,
                new QuietButton("Tonal", QuietButton.Kind.TONAL).kind());
        assertEquals(QuietButton.Kind.PRIMARY,
                new QuietButton("Primary", QuietButton.Kind.PRIMARY).kind());
    }

    @Test
    void paddingComesFromBorderWithoutDuplicatingMargin() {
        QuietButton button = new QuietButton("Action");
        assertEquals(new java.awt.Insets(0, 0, 0, 0), button.getMargin());
        assertEquals(new java.awt.Insets(
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_STANDARD_VERTICAL_INSET,
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_STANDARD_HORIZONTAL_INSET,
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_STANDARD_VERTICAL_INSET,
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_STANDARD_HORIZONTAL_INSET),
                button.getBorder().getBorderInsets(button));

        QuietButton compact = new QuietButton("Compact").compact();
        assertEquals(new java.awt.Insets(0, 0, 0, 0), compact.getMargin());
        assertEquals(new java.awt.Insets(
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_STANDARD_VERTICAL_INSET,
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_COMPACT_HORIZONTAL_INSET,
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_STANDARD_VERTICAL_INSET,
                        org.qainsights.jmeter.ai.gui.theme.UiTokens.BUTTON_COMPACT_HORIZONTAL_INSET),
                compact.getBorder().getBorderInsets(compact));
    }

    @Test
    void paintingDoesNotMutateForeground() {
        QuietButton button = new QuietButton("Send", QuietButton.Kind.PRIMARY);
        button.setSize(96, 32);
        java.util.concurrent.atomic.AtomicInteger changes =
                new java.util.concurrent.atomic.AtomicInteger();
        button.addPropertyChangeListener("foreground", event -> changes.incrementAndGet());

        BufferedImage image = new BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB);
        button.paint(image.getGraphics());
        assertEquals(0, changes.get());

        button.setEnabled(false);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.ThemeColors.secondaryText(),
                button.getForeground());
    }

    @Test
    void primaryButtonPaintsNormalAndHoverStates() {
        QuietButton button = new QuietButton("Send", QuietButton.Kind.PRIMARY);
        button.setSize(96, 32);

        BufferedImage normal = new BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB);
        assertDoesNotThrow(() -> button.paint(normal.getGraphics()));
        assertTrue(hasVisiblePixels(normal));

        button.getModel().setRollover(true);
        BufferedImage hover = new BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB);
        assertDoesNotThrow(() -> button.paint(hover.getGraphics()));
        assertTrue(hasVisiblePixels(hover));
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
