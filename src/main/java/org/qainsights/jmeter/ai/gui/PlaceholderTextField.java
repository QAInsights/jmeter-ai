package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JTextField;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

final class PlaceholderTextField extends JTextField {
    private String placeholder = "";

    PlaceholderTextField() {
    }

    void setPlaceholder(String text) {
        placeholder = text == null ? "" : text;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!getText().isEmpty() || placeholder.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Color color = ThemeColors.secondaryText();
            g.setColor(color);
            g.setFont(getFont());
            int baseline = (getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent();
            g.drawString(placeholder, getInsets().left, baseline);
        } finally {
            g.dispose();
        }
    }
}
