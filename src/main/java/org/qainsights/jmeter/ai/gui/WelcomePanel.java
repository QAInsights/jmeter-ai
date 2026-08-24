package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class WelcomePanel extends JPanel {

    private final String markdown;
    private final JTextPane content;
    private int measuredWidth = -1;

    WelcomePanel(String markdown, Font font) {
        super(new GridBagLayout());
        this.markdown = markdown == null ? "" : markdown;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_6, UiTokens.SPACE_4, UiTokens.SPACE_6, UiTokens.SPACE_4));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);

        content = new JTextPane();
        content.setEditable(false);
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder());
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.putClientProperty("html.disable", Boolean.TRUE);
        applyFont(font);
        render();
        updateContentSize(UiTokens.WELCOME_DEFAULT_WIDTH);
        body.add(content);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, 0, 0);
        add(body, constraints);
    }

    @Override
    public Dimension getPreferredSize() {
        int availableWidth = UiTokens.WELCOME_DEFAULT_WIDTH;
        if (getParent() != null && getParent().getWidth() > 0) {
            availableWidth = getParent().getWidth()
                    - getInsets().left - getInsets().right;
        }
        updateContentSize(availableWidth);
        return super.getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    String getMarkdown() {
        return markdown;
    }

    void applyFont(Font font) {
        if (font != null) {
            content.setFont(font);
            measuredWidth = -1;
            revalidate();
        }
    }

    void applyTheme() {
        content.setForeground(ThemeColors.foreground());
        StyledDocument document = content.getStyledDocument();
        Style style = document.getStyle("default");
        if (style != null) {
            StyleConstants.setForeground(style, ThemeColors.foreground());
        }
        repaint();
    }

    private void updateContentSize(int width) {
        int resolvedWidth = Math.max(UiTokens.WELCOME_MIN_WIDTH, width);
        if (resolvedWidth == measuredWidth) {
            return;
        }
        measuredWidth = resolvedWidth;
        Insets contentInsets = content.getInsets();
        javax.swing.text.View rootView = content.getUI().getRootView(content);
        rootView.setSize(Math.max(1,
                resolvedWidth - contentInsets.left - contentInsets.right), Integer.MAX_VALUE);
        int measuredHeight = (int) Math.ceil(
                rootView.getPreferredSpan(javax.swing.text.View.Y_AXIS));
        int height = Math.max(UiTokens.WELCOME_MIN_HEIGHT, measuredHeight
                + contentInsets.top + contentInsets.bottom + UiTokens.SPACE_8);
        content.setPreferredSize(new Dimension(resolvedWidth, height));
        content.setMinimumSize(new Dimension(0, height));
        content.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    private void render() {
        try {
            StyledDocument document = content.getStyledDocument();
            Style style = document.getStyle("default");
            if (style != null) {
                StyleConstants.setForeground(style, ThemeColors.foreground());
            }
            new MessageProcessor().appendMessage(document, markdown, null, true);
        } catch (BadLocationException e) {
            content.setText(markdown);
        }
    }
}
