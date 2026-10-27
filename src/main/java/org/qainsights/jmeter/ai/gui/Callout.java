package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class Callout extends JPanel {

    enum Tone {
        WARNING,
        INFO,
        SECONDARY
    }

    private final JComponent content;
    private final Tone tone;

    Callout(JTextArea area, Tone tone) {
        this((JComponent) area, tone);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
    }

    Callout(JComponent content, Tone tone) {
        super(new BorderLayout());
        this.content = content;
        this.tone = tone == null ? Tone.INFO : tone;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_2,
                UiTokens.SPACE_3 + 3, UiTokens.SPACE_2, UiTokens.SPACE_3));
        content.setOpaque(false);
        add(content, BorderLayout.CENTER);
        refreshTheme();
    }

    void refreshTheme() {
        if (content instanceof JTextArea area) {
            area.setForeground(ThemeColors.foreground());
        }
        repaint();
    }

    private Color toneColor() {
        return switch (tone) {
            case WARNING -> ThemeColors.warning();
            case INFO -> ThemeColors.info();
            case SECONDARY -> ThemeColors.secondaryText();
        };
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1;
            int height = getHeight() - 1;
            if (width <= 0 || height <= 0) {
                return;
            }
            Color toneColor = toneColor();
            g.setColor(ThemeColors.blend(toneColor, ThemeColors.elevatedSurface(), 0.12f));
            g.fillRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                    UiTokens.RADIUS_SMALL * 2);
            g.setColor(ThemeColors.blend(toneColor, ThemeColors.separator(), 0.30f));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                    UiTokens.RADIUS_SMALL * 2);
            g.setColor(toneColor);
            g.fillRoundRect(UiTokens.SPACE_1, UiTokens.SPACE_2, 3,
                    Math.max(1, height - UiTokens.SPACE_2 * 2), 3, 3);
        } finally {
            g.dispose();
        }
    }
}
