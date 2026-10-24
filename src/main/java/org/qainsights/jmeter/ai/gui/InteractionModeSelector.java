package org.qainsights.jmeter.ai.gui;

import java.awt.FlowLayout;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class InteractionModeSelector extends JPanel {
    private final JRadioButton chatButton = new JRadioButton("Chat");
    private final JRadioButton agentButton = new JRadioButton("Agent");
    private final boolean agentAvailable;
    private boolean agentWasSelected;

    InteractionModeSelector(boolean agentAvailable) {
        super(new FlowLayout(FlowLayout.LEFT, UiTokens.SPACE_1, 0));
        this.agentAvailable = agentAvailable;
        setOpaque(false);

        configure(chatButton, "Use plain chat without JMeter tools");
        configure(agentButton, agentAvailable
                ? "Allow the AI to use JMeter tools"
                : "Agent mode is off. Set jmeter.ai.agent.enabled=true in user.properties and restart JMeter");
        agentButton.setEnabled(agentAvailable);

        ButtonGroup group = new ButtonGroup();
        group.add(chatButton);
        group.add(agentButton);
        (agentAvailable ? agentButton : chatButton).setSelected(true);

        add(chatButton);
        add(agentButton);
        getAccessibleContext().setAccessibleName("Interaction mode");
        getAccessibleContext().setAccessibleDescription(
                "Choose Chat for conversation or Agent to allow JMeter tools");
        applyTheme();
    }

    boolean isAgentSelected() {
        return agentButton.isSelected();
    }

    void setModelSupportsAgent(boolean supported) {
        if (!agentAvailable) {
            return;
        }
        if (supported) {
            agentButton.setEnabled(true);
            agentButton.setToolTipText("Allow the AI to use JMeter tools");
            if (agentWasSelected) {
                agentButton.setSelected(true);
                agentWasSelected = false;
            }
        } else {
            if (agentButton.isSelected()) {
                agentWasSelected = true;
            }
            chatButton.setSelected(true);
            agentButton.setEnabled(false);
            agentButton.setToolTipText(
                    "Agent mode isn't available for Apple's on-device model. Pick a cloud model, Codex, Claude Code "
                            + "or OpenCode to use Agent");
        }
        applyTheme();
    }

    JRadioButton chatButton() {
        return chatButton;
    }

    JRadioButton agentButton() {
        return agentButton;
    }

    void applyTheme() {
        chatButton.setForeground(ThemeColors.foreground());
        agentButton.setForeground(agentButton.isEnabled()
                ? ThemeColors.foreground()
                : ThemeColors.secondaryText());
        repaint();
    }

    private static void configure(JRadioButton button, String tooltip) {
        button.setOpaque(false);
        button.setFocusPainted(true);
        button.setFont(UiTokens.caption(button.getFont()));
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(button.getText() + " mode");
    }
}
