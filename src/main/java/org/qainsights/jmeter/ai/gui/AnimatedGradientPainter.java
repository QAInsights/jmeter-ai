package org.qainsights.jmeter.ai.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;

/**
 * Shared painting logic for the rotating, Google-Gemini-style multi-color gradient
 * stroke. Originally lived solely inside {@link GeminiBorderPanel} (the chat input's
 * "thinking" border); extracted here so the JMeter tree's agent-activity glow (see
 * {@code AgentGlowBorder}) can reuse the exact same visual treatment rather than
 * reimplementing it.
 * <p>
 * Pure {@code Graphics2D} painting with no component/state dependencies - callers own
 * the rotation angle (advance it externally, e.g. via a {@code Timer}) and any fade
 * alpha.
 */
public final class AnimatedGradientPainter {

    /** The five-stop Gemini-style color cycle (blue -> purple -> pink/red -> cyan -> blue). */
    private static final Color[] COLORS = {
            new Color(0x42, 0x85, 0xF4),
            new Color(0x9b, 0x51, 0xe0),
            new Color(0xea, 0x43, 0x35),
            new Color(0x24, 0xb4, 0xf4),
            new Color(0x42, 0x85, 0xF4)
    };
    private static final float[] FRACTIONS = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};

    private AnimatedGradientPainter() {
    }

    /**
     * Paints a rotating-gradient rounded-rect stroke inside {@code (0, 0, width, height)}
     * of the given graphics context. Does not dispose {@code g2d}; callers should paint
     * onto a disposable copy (e.g. {@code (Graphics2D) g.create()}).
     *
     * @param g2d                    destination graphics
     * @param width                  the paint area's width
     * @param height                 the paint area's height
     * @param rotationAngleDegrees   current rotation angle (any int; wrapped internally)
     * @param strokeWidth            stroke thickness in pixels
     * @param arcRadius              rounded-rect corner radius in pixels
     * @param inset                  padding from the edges before the stroke is drawn
     * @param alpha                  overall opacity multiplier (0f-1f); use for fade-in/out.
     *                                Values &lt;= 0 paint nothing.
     */
    public static void paintRotatingBorder(Graphics2D g2d, int width, int height, int rotationAngleDegrees,
                                            float strokeWidth, int arcRadius, int inset, float alpha) {
        if (width <= 0 || height <= 0 || alpha <= 0f) {
            return;
        }
        double angleRad = Math.toRadians(((rotationAngleDegrees % 360) + 360) % 360);
        float x1 = (float) (width / 2.0 + (width / 2.0) * Math.cos(angleRad));
        float y1 = (float) (height / 2.0 + (height / 2.0) * Math.sin(angleRad));
        float x2 = (float) (width / 2.0 - (width / 2.0) * Math.cos(angleRad));
        float y2 = (float) (height / 2.0 - (height / 2.0) * Math.sin(angleRad));
        if (x1 == x2 && y1 == y2) {
            // LinearGradientPaint requires distinct start/end points.
            x2 += 0.01f;
        }

        Composite originalComposite = g2d.getComposite();
        float clampedAlpha = Math.min(1f, alpha);
        if (clampedAlpha < 1f) {
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clampedAlpha));
        }
        LinearGradientPaint paint = new LinearGradientPaint(x1, y1, x2, y2, FRACTIONS, COLORS);
        g2d.setPaint(paint);
        g2d.setStroke(new BasicStroke(strokeWidth));
        g2d.drawRoundRect(inset, inset, width - inset * 2, height - inset * 2, arcRadius, arcRadius);
        g2d.setComposite(originalComposite);
    }
}
