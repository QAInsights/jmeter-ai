package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.UIManager;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * The small circular stop button shown bottom-right of the input row while
 * the AI is processing (ChatGPT-style): an accent-tinted disc with a stop
 * square, deepening on hover. Drawn rather than emoji so it renders on any
 * font/look-and-feel.
 */
class StopButton extends JButton {

    private static final int SIZE = 24;

    StopButton(Runnable onStop) {
        setToolTipText("Stop the current response");
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(SIZE, SIZE));
        addActionListener(e -> onStop.run());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Color panelBg = UIManager.getColor("Panel.background");
            if (panelBg == null) {
                panelBg = getBackground();
            }
            float tint = getModel().isRollover() ? 0.45f : 0.28f;
            java.awt.Color disc = ThemeColors.blend(ThemeColors.accent(), panelBg, tint);
            int d = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            g2.setColor(disc);
            g2.fillOval(x, y, d, d);
            g2.setColor(ThemeColors.blend(ThemeColors.accent(), panelBg, 0.7f));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawOval(x, y, d, d);
            // stop square, centered
            int s = Math.round(d * 0.4f);
            g2.setColor(ThemeColors.blend(ThemeColors.accent(), panelBg, 0.85f));
            g2.fillRoundRect(x + (d - s) / 2, y + (d - s) / 2, s, s, 3, 3);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
