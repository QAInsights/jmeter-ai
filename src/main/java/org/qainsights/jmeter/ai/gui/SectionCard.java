package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Component;
import java.awt.Font;
import java.awt.LayoutManager;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class SectionCard extends JPanel {

    private final JPanel header = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
    private final JLabel titleLabel = new JLabel();
    private final JLabel captionLabel = new JLabel();
    private final JPanel body = new JPanel(new BorderLayout());
    private Component headerTrailing;

    SectionCard(String title) {
        this(title, null);
    }

    SectionCard(String title, String caption) {
        super(new BorderLayout(0, UiTokens.SPACE_2));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_3, UiTokens.SPACE_4,
                UiTokens.SPACE_3, UiTokens.SPACE_4));
        header.setOpaque(false);
        titleLabel.setText(title);
        header.add(titleLabel, BorderLayout.WEST);
        captionLabel.setHorizontalAlignment(JLabel.RIGHT);
        header.add(captionLabel, BorderLayout.EAST);
        body.setOpaque(false);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        setCaption(caption);
        refreshFonts();
    }

    JPanel body() {
        return body;
    }

    JLabel titleLabel() {
        return titleLabel;
    }

    void setTitle(String title) {
        titleLabel.setText(title);
    }

    void setCaption(String caption) {
        captionLabel.setText(caption == null ? "" : caption);
        captionLabel.setVisible(caption != null && !caption.isBlank());
    }

    void setHeaderTrailing(Component trailing) {
        if (headerTrailing != null) {
            header.remove(headerTrailing);
        }
        headerTrailing = trailing;
        if (trailing != null) {
            header.add(trailing, BorderLayout.EAST);
        } else {
            header.add(captionLabel, BorderLayout.EAST);
        }
        revalidate();
        repaint();
    }

    void setBodyLayout(LayoutManager layout) {
        body.setLayout(layout);
    }

    void refreshFonts() {
        Font base = UIManager.getFont("Label.font");
        titleLabel.setFont(UiTokens.label(base));
        titleLabel.setForeground(ThemeColors.accent());
        captionLabel.setFont(UiTokens.caption(base));
        captionLabel.setForeground(ThemeColors.secondaryText());
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
            g.setColor(ThemeColors.elevatedSurface());
            g.fillRoundRect(0, 0, width, height, UiTokens.RADIUS_MEDIUM * 2,
                    UiTokens.RADIUS_MEDIUM * 2);
            g.setColor(ThemeColors.separator());
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, width, height, UiTokens.RADIUS_MEDIUM * 2,
                    UiTokens.RADIUS_MEDIUM * 2);
        } finally {
            g.dispose();
        }
    }
}
