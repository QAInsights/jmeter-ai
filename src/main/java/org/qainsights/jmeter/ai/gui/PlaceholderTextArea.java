package org.qainsights.jmeter.ai.gui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.JTextArea;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * A {@link JTextArea} that paints a ghost "placeholder" hint when it is empty
 * and unfocused content-wise - the standard composer affordance from modern
 * chat tools. The hint is painted (never inserted into the document), so it
 * never interferes with key handling, IME composition, or the message text.
 */
public class PlaceholderTextArea extends JTextArea {

    private String placeholder = "";

    public PlaceholderTextArea(int rows, int columns) {
        super(rows, columns);
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!getText().isEmpty() || placeholder.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );
            g2.setColor(ThemeColors.secondaryText());
            g2.setFont(getFont().deriveFont(java.awt.Font.ITALIC));
            Insets insets = getInsets();
            var fm = g2.getFontMetrics();
            g2.drawString(
                placeholder,
                insets.left,
                insets.top + fm.getAscent()
            );
        } finally {
            g2.dispose();
        }
    }
}
