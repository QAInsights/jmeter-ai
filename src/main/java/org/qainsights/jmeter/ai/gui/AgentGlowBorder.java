package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import javax.swing.border.Border;

/**
 * Paints {@link AnimatedGradientPainter}'s rotating gradient stroke as a JTree
 * cell's border - the JMeter tree row equivalent of {@link GeminiBorderPanel}'s
 * chat-input "thinking" glow.
 * <p>
 * Two instances are used per {@code AgentActivityCellRenderer}: one that actually
 * paints (for the currently-active row) and one that paints nothing (for every
 * other row), both sharing the same {@link #getBorderInsets} - so a row's size
 * never jumps when the glow turns on/off for it, only whether the stroke itself is
 * drawn.
 */
final class AgentGlowBorder implements Border {

    private static final Insets INSETS = new Insets(2, 3, 2, 3);
    private static final float STROKE_WIDTH = 2.0f;
    private static final int ARC_RADIUS = 8;
    private static final int PAINT_INSET = 1;

    private final IntSupplier rotationAngleDegrees;
    private final Supplier<Float> alpha;
    private final boolean paints;

    AgentGlowBorder(IntSupplier rotationAngleDegrees, Supplier<Float> alpha, boolean paints) {
        this.rotationAngleDegrees = rotationAngleDegrees;
        this.alpha = alpha;
        this.paints = paints;
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return INSETS;
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        if (!paints) {
            return;
        }
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.translate(x, y);
            AnimatedGradientPainter.paintRotatingBorder(g2d, width, height, rotationAngleDegrees.getAsInt(),
                    STROKE_WIDTH, ARC_RADIUS, PAINT_INSET, alpha.get());
        } finally {
            g2d.dispose();
        }
    }
}
