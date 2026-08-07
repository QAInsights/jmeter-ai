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
 * Hand-drawn dropdown chevron for the model selector button (same rationale
 * as {@link AttachIcons}: text glyphs like ▾ are unreliable across the fonts
 * JMeter look-and-feels pick, so the affordance paints its own).
 */
final class ChevronIcons {

    private ChevronIcons() {
    }

    /** A small downward-pointing chevron. */
    static Icon down(int size) {
        return new Icon() {
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
                    g2.setColor(ThemeColors.foreground());
                    g2.setStroke(new BasicStroke(Math.max(1.2f, size / 8f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    GeneralPath chevron = new GeneralPath();
                    chevron.moveTo(x + 0.2 * size, y + 0.35 * size);
                    chevron.lineTo(x + 0.5 * size, y + 0.68 * size);
                    chevron.lineTo(x + 0.8 * size, y + 0.35 * size);
                    g2.draw(chevron);
                } finally {
                    g2.dispose();
                }
            }
        };
    }
}
