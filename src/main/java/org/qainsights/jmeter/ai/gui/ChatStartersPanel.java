package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class ChatStartersPanel extends JPanel {
    private final JLabel heading = new JLabel("Try with your test plan");
    private final List<QuietButton> buttons = new ArrayList<>();
    private List<ChatStarterSuggestion> suggestions = List.of();
    private Consumer<ChatStarterSuggestion> acceptSuggestion;

    ChatStartersPanel(Font font) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, UiTokens.SPACE_3, 0));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        applyFont(font);
        applyTheme();
        setVisible(false);
    }

    void setSuggestions(List<ChatStarterSuggestion> values, Consumer<ChatStarterSuggestion> accept) {
        acceptSuggestion = accept;
        List<ChatStarterSuggestion> visible = accept == null ? List.of() : List.copyOf(values);
        if (suggestions.equals(visible)) {
            return;
        }
        suggestions = visible;
        removeAll();
        buttons.clear();
        if (!visible.isEmpty()) {
            add(heading);
            add(Box.createVerticalStrut(UiTokens.SPACE_2));
        }
        for (ChatStarterSuggestion value : visible) {
            if (!buttons.isEmpty()) {
                add(Box.createVerticalStrut(UiTokens.SPACE_2));
            }
            QuietButton button = new QuietButton(value.label(), QuietButton.Kind.OUTLINED).bodyFont();
            button.setFont(getFont());
            button.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMargin(new Insets(UiTokens.SPACE_2, UiTokens.SPACE_3,
                    UiTokens.SPACE_2, UiTokens.SPACE_3));
            sizeButton(button);
            button.setToolTipText(value.prompt());
            button.getAccessibleContext().setAccessibleDescription("Fill the message box with: " + value.prompt());
            button.addActionListener(event -> {
                if (acceptSuggestion != null && suggestions.contains(value)) {
                    acceptSuggestion.accept(value);
                }
            });
            buttons.add(button);
            add(button);
        }
        setVisible(!visible.isEmpty());
        revalidate();
        repaint();
    }

    List<QuietButton> suggestionButtons() {
        return List.copyOf(buttons);
    }

    void applyFont(Font font) {
        setFont(font);
        heading.setFont(UiTokens.caption(font));
        for (QuietButton button : buttons) {
            button.setFont(font);
            sizeButton(button);
        }
        revalidate();
    }

    void applyTheme() {
        heading.setForeground(ThemeColors.secondaryText());
        repaint();
    }

    private void sizeButton(QuietButton button) {
        int height = Math.max(UiTokens.CONTROL_HEIGHT, button.getPreferredSize().height);
        button.setMinimumSize(new Dimension(0, height));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }
}
