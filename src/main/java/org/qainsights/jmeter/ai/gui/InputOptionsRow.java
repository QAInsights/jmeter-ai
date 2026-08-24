package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/**
 * The options area below the message input: model and reasoning controls stay
 * next to the composer, while input actions and status occupy a compact row.
 */
class InputOptionsRow extends JPanel {

    private final JPanel modelSlot;
    private final JPanel optionsStrip;
    private final JPanel hintPanel;
    private final JPanel statsPanel;
    private final JPanel actionPanel;
    private final JLabel hintLabel;
    private final QuietButton sendButton;
    private StopButton stopButton;

    InputOptionsRow(Component popupParent, AttachmentBar attachmentBar, JButton attachButton) {
        this(popupParent, attachmentBar, attachButton, () -> { });
    }

    InputOptionsRow(Component popupParent, AttachmentBar attachmentBar,
                    JButton attachButton, Runnable onSend) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2));

        modelSlot = new JPanel(new BorderLayout());
        modelSlot.setOpaque(false);
        modelSlot.setAlignmentX(Component.LEFT_ALIGNMENT);
        modelSlot.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTokens.CONTROL_HEIGHT + UiTokens.SPACE_1));
        modelSlot.setVisible(false);
        add(modelSlot);

        attachButton.setIcon(AttachIcons.paperclip(15));
        attachButton.setToolTipText("Attach a file (jmeter.log, results, or any text file)");
        attachButton.getAccessibleContext().setAccessibleName("Attach file");
        attachButton.addActionListener(e -> {
            AttachMenu menu = new AttachMenu(popupParent, attachmentBar);
            menu.show(attachButton, 0, attachButton.getHeight());
        });

        optionsStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.SPACE_1, 0));
        optionsStrip.setOpaque(false);
        optionsStrip.add(attachButton);

        statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_2, 0));
        statsPanel.setOpaque(false);

        hintLabel = new JLabel("Enter to send · Shift+Enter for newline");
        hintLabel.setForeground(ThemeColors.secondaryText());
        hintLabel.setFont(UiTokens.caption(hintLabel.getFont()));
        hintPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_2, 0));
        hintPanel.setOpaque(false);
        hintPanel.add(hintLabel);

        JPanel statusStrip = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        statusStrip.setOpaque(false);
        statusStrip.add(statsPanel, BorderLayout.WEST);
        statusStrip.add(hintPanel, BorderLayout.EAST);

        sendButton = new QuietButton("", QuietButton.Kind.PRIMARY).iconOnly();
        sendButton.setIcon(ActionIcons.send(16));
        sendButton.setToolTipText("Send message");
        sendButton.getAccessibleContext().setAccessibleName("Send message");
        sendButton.addActionListener(e -> onSend.run());

        actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionPanel.setOpaque(false);
        actionPanel.add(sendButton);

        JPanel actionRow = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        actionRow.setOpaque(false);
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionRow.add(optionsStrip, BorderLayout.WEST);
        actionRow.add(statusStrip, BorderLayout.CENTER);
        actionRow.add(actionPanel, BorderLayout.EAST);
        add(actionRow);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateHintVisibility(getWidth());
            }
        });
    }

    void updateHintVisibility(int width) {
        hintPanel.setVisible(width >= UiTokens.HINT_VISIBILITY_WIDTH);
    }

    boolean isHintVisible() {
        return hintPanel.isVisible();
    }

    void setModelRow(JPanel row) {
        modelSlot.removeAll();
        if (row != null) {
            modelSlot.add(row, BorderLayout.CENTER);
        }
        modelSlot.setVisible(row != null);
        revalidate();
        repaint();
    }

    /**
     * Swaps the send action for the circular stop button while the AI is
     * processing. The send action returns on {@link #hideStop()}.
     */
    void showStop(Runnable onStop) {
        if (stopButton == null) {
            stopButton = new StopButton(onStop);
        }
        actionPanel.removeAll();
        actionPanel.add(stopButton);
        revalidate();
        repaint();
    }

    /** Hides the stop button and restores the send action. */
    void hideStop() {
        actionPanel.removeAll();
        actionPanel.add(sendButton);
        revalidate();
        repaint();
    }

    /** True while the stop button is showing (for tests). */
    boolean isStopShowing() {
        return actionPanel.getComponentCount() == 1
                && actionPanel.getComponent(0) instanceof StopButton;
    }

    /** Adds another input-adjacent option button next to the paperclip. */
    void addOption(JComponent component) {
        optionsStrip.add(component);
        revalidate();
    }

    /** Installs the context-stats label between the options strip and the hint. */
    void setStatsComponent(JComponent component) {
        statsPanel.removeAll();
        statsPanel.add(component);
        revalidate();
    }

    void applyTheme() {
        hintLabel.setForeground(ThemeColors.secondaryText());
        for (Component component : statsPanel.getComponents()) {
            component.setForeground(ThemeColors.secondaryText());
        }
        repaint();
    }

    void setSendEnabled(boolean enabled) {
        sendButton.setEnabled(enabled);
    }

    JButton sendButton() {
        return sendButton;
    }

    /** The stats component, or null when none was installed (for tests). */
    JComponent statsComponent() {
        return statsPanel.getComponentCount() == 0
                ? null
                : (JComponent) statsPanel.getComponent(0);
    }

    /** Number of option buttons currently in the strip (for tests). */
    int getOptionCount() {
        return optionsStrip.getComponentCount();
    }
}
