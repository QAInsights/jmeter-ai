package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.Timer;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * The header "Donate" button: a regular button (native look and feel, like
 * every other header button) with a hand-drawn coffee icon and a rotating
 * Gemini-style gradient outline. Replaces the old custom warm-tinted pill,
 * which clashed with every theme - the CTA now stands out via the same
 * animated gradient stroke used elsewhere in the app ({@link AnimatedGradientPainter},
 * shared with {@link GeminiBorderPanel}'s "thinking" border and the JMeter
 * tree's agent-activity glow) rather than a bespoke fill. Extracted from
 * {@link AiChatPanel} to keep it within the project's line limit.
 */
class DonateButton extends JButton {

    private static final Color WARM = new Color(255, 149, 0);
    private static final float STROKE_WIDTH = 2.0f;
    private static final int ARC_RADIUS = 10;
    private static final int PAINT_INSET = 1;

    private int rotationAngle = 0;
    private final Timer animationTimer;

    DonateButton() {
        super("Donate");
        setIcon(new CoffeeIcon(14));
        setIconTextGap(6);
        setToolTipText("Support this project as it takes time, tokens and resources to build and maintain");
        setFocusPainted(false);
        setFont(getFont().deriveFont(Font.BOLD, 12f));
        setMargin(new Insets(5, 14, 5, 14));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Always-on rotating gradient outline (same painter as GeminiBorderPanel/
        // AgentGlowBorder) - this is a persistent call-to-action rather than a
        // transient state, so it animates continuously for as long as it's showing.
        animationTimer = new Timer(30, e -> {
            rotationAngle = (rotationAngle + 4) % 360;
            repaint();
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        animationTimer.start();
    }

    @Override
    public void removeNotify() {
        animationTimer.stop();
        super.removeNotify();
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            AnimatedGradientPainter.paintRotatingBorder(g2, getWidth(), getHeight(), rotationAngle,
                    STROKE_WIDTH, ARC_RADIUS, PAINT_INSET, 1f);
        } finally {
            g2.dispose();
        }
    }

    /** Hand-drawn coffee cup with steam, stroked in the warm accent color. */
    private static final class CoffeeIcon implements Icon {
        private final int size;

        CoffeeIcon(int size) {
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ThemeColors.blend(WARM, ThemeColors.foreground(), 0.75f));
                g2.setStroke(new BasicStroke(Math.max(1.1f, size / 11f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float s = size;
                // cup body
                GeneralPath cup = new GeneralPath();
                cup.moveTo(x + 0.16f * s, y + 0.38f * s);
                cup.lineTo(x + 0.66f * s, y + 0.38f * s);
                cup.lineTo(x + 0.62f * s, y + 0.78f * s);
                cup.quadTo(x + 0.60f * s, y + 0.88f * s, x + 0.50f * s, y + 0.88f * s);
                cup.lineTo(x + 0.30f * s, y + 0.88f * s);
                cup.quadTo(x + 0.20f * s, y + 0.88f * s, x + 0.18f * s, y + 0.78f * s);
                cup.closePath();
                g2.draw(cup);
                // handle
                GeneralPath handle = new GeneralPath();
                handle.moveTo(x + 0.66f * s, y + 0.44f * s);
                handle.quadTo(x + 0.92f * s, y + 0.46f * s, x + 0.88f * s, y + 0.60f * s);
                handle.quadTo(x + 0.86f * s, y + 0.70f * s, x + 0.64f * s, y + 0.70f * s);
                g2.draw(handle);
                // steam
                g2.drawLine(Math.round(x + 0.30f * s), Math.round(y + 0.14f * s),
                        Math.round(x + 0.30f * s), Math.round(y + 0.26f * s));
                g2.drawLine(Math.round(x + 0.48f * s), Math.round(y + 0.10f * s),
                        Math.round(x + 0.48f * s), Math.round(y + 0.26f * s));
            } finally {
                g2.dispose();
            }
        }
    }
}
