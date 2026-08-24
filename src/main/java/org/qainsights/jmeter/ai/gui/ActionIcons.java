package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

final class ActionIcons {

    private ActionIcons() {
    }

    static Icon plus(int size) {
        return icon(size, (g2, x, y, s) -> {
            int center = s / 2;
            int inset = Math.max(2, Math.round(s * 0.22f));
            g2.drawLine(x + center, y + inset, x + center, y + s - inset);
            g2.drawLine(x + inset, y + center, x + s - inset, y + center);
        });
    }

    static Icon send(int size) {
        return icon(size, (g2, x, y, s) -> {
            int center = s / 2;
            int top = Math.max(2, Math.round(s * 0.18f));
            int side = Math.max(3, Math.round(s * 0.28f));
            int bottom = s - Math.max(2, Math.round(s * 0.18f));
            g2.drawLine(x + center, y + top, x + center, y + bottom);
            g2.drawLine(x + center, y + top, x + side, y + Math.round(s * 0.45f));
            g2.drawLine(x + center, y + top, x + s - side, y + Math.round(s * 0.45f));
        });
    }

    static Icon chevronUp(int size) {
        return chevron(size, true);
    }

    static Icon chevronDown(int size) {
        return chevron(size, false);
    }

    static Icon more(int size) {
        return icon(size, (g2, x, y, s) -> {
            int diameter = Math.max(2, Math.round(s * 0.14f));
            int centerX = x + (s - diameter) / 2;
            for (float position : new float[] {0.23f, 0.5f, 0.77f}) {
                int centerY = y + Math.round(s * position) - diameter / 2;
                g2.fillOval(centerX, centerY, diameter, diameter);
            }
        });
    }

    static Icon copy(int size) {
        return icon(size, (g2, x, y, s) -> {
            int inset = Math.max(2, Math.round(s * 0.16f));
            int offset = Math.max(2, Math.round(s * 0.18f));
            int width = s - inset * 2 - offset;
            int height = s - inset * 2 - offset;
            int arc = Math.max(2, Math.round(s * 0.16f));
            g2.drawRoundRect(x + inset + offset, y + inset, width, height, arc, arc);
            g2.drawRoundRect(x + inset, y + inset + offset, width, height, arc, arc);
        });
    }

    private static Icon chevron(int size, boolean up) {
        return icon(size, (g2, x, y, s) -> {
            int left = x + Math.round(s * 0.2f);
            int right = x + Math.round(s * 0.8f);
            int center = x + s / 2;
            int near = y + Math.round(s * (up ? 0.62f : 0.38f));
            int far = y + Math.round(s * (up ? 0.34f : 0.66f));
            g2.drawLine(left, near, center, far);
            g2.drawLine(center, far, right, near);
        });
    }

    private static Icon icon(int size, Painter painter) {
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
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    Color foreground = component != null && component.isEnabled()
                            ? component.getForeground() : ThemeColors.secondaryText();
                    g2.setColor(foreground != null ? foreground : ThemeColors.foreground());
                    g2.setStroke(new BasicStroke(Math.max(1.4f, size / 9f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    painter.paint(g2, x, y, size);
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    private interface Painter {
        void paint(Graphics2D graphics, int x, int y, int size);
    }
}
