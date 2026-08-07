package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import javax.swing.Icon;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * Hand-drawn pin/unpin stars for the model selector (same rationale as
 * {@link AttachIcons}: the ★/☆ glyphs are unreliable on stock button fonts,
 * so the toggle paints its own). The outline star marks "not pinned", the
 * filled accent star "pinned".
 */
final class StarIcons {

    private StarIcons() {
    }

    /** Outline star for the unpinned state. */
    static Icon outline(int size) {
        return new StarIcon(size, false);
    }

    /** Filled accent star for the pinned state. */
    static Icon filled(int size) {
        return new StarIcon(size, true);
    }

    /** Five-pointed star centered in the icon box. */
    static GeneralPath starPath(int x, int y, int size) {
        double cx = x + size / 2.0;
        double cy = y + size / 2.0;
        double outer = size * 0.46;
        double inner = outer * 0.42;
        GeneralPath path = new GeneralPath();
        for (int i = 0; i < 10; i++) {
            double radius = (i % 2 == 0) ? outer : inner;
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            double px = cx + radius * Math.cos(angle);
            double py = cy + radius * Math.sin(angle);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.closePath();
        return path;
    }

    private static final class StarIcon implements Icon {
        private final int size;
        private final boolean filled;

        StarIcon(int size, boolean filled) {
            this.size = size;
            this.filled = filled;
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
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                GeneralPath star = starPath(x, y, size);
                if (filled) {
                    g2.setColor(ThemeColors.accent());
                    g2.fill(star);
                } else {
                    g2.setColor(ThemeColors.foreground());
                    g2.setStroke(new BasicStroke(Math.max(1f, size / 12f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(star);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
