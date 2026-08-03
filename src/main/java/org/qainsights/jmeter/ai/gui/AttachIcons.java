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
 * Hand-drawn icons for the attachment UI. The 📎/📄 emoji render as tofu
 * rectangles on fonts without emoji coverage (the default button font on
 * Windows), so the paperclip button and the file chips paint their glyphs
 * instead - crisp on any look-and-feel.
 */
final class AttachIcons {

    private AttachIcons() {
    }

    /** The paperclip glyph for the attach button. */
    static Icon paperclip(int size) {
        return new PaperclipIcon(size);
    }

    /** The small document glyph for file chips. */
    static Icon document(int size) {
        return new DocumentIcon(size);
    }

    private abstract static class BaseIcon implements Icon {
        private final int size;

        BaseIcon(int size) {
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
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ThemeColors.foreground());
                g2.setStroke(new BasicStroke(Math.max(1.1f, size / 10f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                paint(g2, x, y, size);
            } finally {
                g2.dispose();
            }
        }

        abstract void paint(Graphics2D g2, int x, int y, int size);
    }

    /** Paperclip: a tall outer loop with a smaller inner loop. */
    private static final class PaperclipIcon extends BaseIcon {
        PaperclipIcon(int size) {
            super(size);
        }

        @Override
        void paint(Graphics2D g2, int x, int y, int s) {
            GeneralPath path = new GeneralPath();
            // outer loop: down the left, round the bottom, up the right, round the top
            path.moveTo(x + 0.32 * s, y + 0.30 * s);
            path.lineTo(x + 0.32 * s, y + 0.68 * s);
            path.quadTo(x + 0.32 * s, y + 0.88 * s, x + 0.52 * s, y + 0.88 * s);
            path.quadTo(x + 0.78 * s, y + 0.88 * s, x + 0.78 * s, y + 0.62 * s);
            path.lineTo(x + 0.78 * s, y + 0.28 * s);
            path.quadTo(x + 0.78 * s, y + 0.12 * s, x + 0.62 * s, y + 0.12 * s);
            path.quadTo(x + 0.46 * s, y + 0.12 * s, x + 0.46 * s, y + 0.28 * s);
            // inner loop: down and round the small bottom
            path.lineTo(x + 0.46 * s, y + 0.62 * s);
            path.quadTo(x + 0.46 * s, y + 0.74 * s, x + 0.56 * s, y + 0.74 * s);
            path.quadTo(x + 0.66 * s, y + 0.74 * s, x + 0.66 * s, y + 0.62 * s);
            path.lineTo(x + 0.66 * s, y + 0.42 * s);
            g2.draw(path);
        }
    }

    /** Document: page outline with a folded corner and two text lines. */
    private static final class DocumentIcon extends BaseIcon {
        DocumentIcon(int size) {
            super(size);
        }

        @Override
        void paint(Graphics2D g2, int x, int y, int s) {
            GeneralPath page = new GeneralPath();
            page.moveTo(x + 0.24 * s, y + 0.12 * s);
            page.lineTo(x + 0.62 * s, y + 0.12 * s);
            page.lineTo(x + 0.78 * s, y + 0.28 * s);
            page.lineTo(x + 0.78 * s, y + 0.88 * s);
            page.lineTo(x + 0.24 * s, y + 0.88 * s);
            page.closePath();
            g2.draw(page);
            // fold
            GeneralPath fold = new GeneralPath();
            fold.moveTo(x + 0.62 * s, y + 0.12 * s);
            fold.lineTo(x + 0.62 * s, y + 0.28 * s);
            fold.lineTo(x + 0.78 * s, y + 0.28 * s);
            g2.draw(fold);
            // text lines
            g2.drawLine(Math.round(x + 0.34f * s), Math.round(y + 0.48f * s),
                    Math.round(x + 0.68f * s), Math.round(y + 0.48f * s));
            g2.drawLine(Math.round(x + 0.34f * s), Math.round(y + 0.66f * s),
                    Math.round(x + 0.68f * s), Math.round(y + 0.66f * s));
        }
    }
}
