package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/** Small animated "thinking" row shown while the AI works. */
final class ThinkingRow extends JPanel {
    private final JLabel label;
    private final Timer timer;
    private int dots;

    ThinkingRow() {
        super(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        label = new JLabel("Feather Wand is thinking");
        label.setFont(UiTokens.caption(label.getFont()).deriveFont(Font.ITALIC));
        label.setForeground(ThemeColors.accent());
        label.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_3,
                UiTokens.SPACE_2, UiTokens.SPACE_3));
        add(label);
        timer = new Timer(400, e -> advance());
        timer.start();
    }

    void applyTheme() {
        label.setForeground(ThemeColors.accent());
        repaint();
    }

    void dispose() {
        timer.stop();
    }

    private void advance() {
        dots = (dots + 1) % 4;
        label.setText("Feather Wand is thinking" + ".".repeat(dots));
    }
}
