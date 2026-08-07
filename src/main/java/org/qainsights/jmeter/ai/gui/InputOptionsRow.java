package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * The options row below the message input: input-adjacent action buttons on
 * the left (the paperclip today, room for future options like voice control)
 * and the keyboard hint on the right. Extracted from {@link AiChatPanel} to
 * keep that file within the project's line limit.
 */
class InputOptionsRow extends JPanel {

    private final JPanel optionsStrip;
    private final JPanel hintPanel;
    private final JPanel statsPanel;
    private StopButton stopButton;

    InputOptionsRow(Component popupParent, AttachmentBar attachmentBar, JButton attachButton) {
        super(new BorderLayout());
        setOpaque(false);

        attachButton.setIcon(AttachIcons.paperclip(14));
        attachButton.setToolTipText("Attach a file (jmeter.log, results, or any text file)");
        attachButton.addActionListener(e -> {
            AttachMenu menu = new AttachMenu(popupParent, attachmentBar);
            menu.show(attachButton, 0, attachButton.getHeight());
        });

        optionsStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        optionsStrip.setOpaque(false);
        optionsStrip.add(attachButton);
        add(optionsStrip, BorderLayout.WEST);

        // Context/cost stats sit just left of the hint. A dedicated CENTER
        // slot, NOT the hint panel: showStop/hideStop wipe the hint panel.
        statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        statsPanel.setOpaque(false);
        add(statsPanel, BorderLayout.CENTER);

        JLabel hintLabel = new JLabel("Enter to send · Shift+Enter for newline");
        hintLabel.setForeground(ThemeColors.secondaryText());
        hintLabel.setFont(hintLabel.getFont().deriveFont(hintLabel.getFont().getSize2D() - 2f));
        hintPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        hintPanel.setOpaque(false);
        hintPanel.add(hintLabel);
        add(hintPanel, BorderLayout.EAST);
    }

    /**
     * Swaps the keyboard hint for the circular stop button (shown while the
     * AI is processing). The hint returns on {@link #hideStop()}.
     */
    void showStop(Runnable onStop) {
        if (stopButton == null) {
            stopButton = new StopButton(onStop);
        }
        hintPanel.removeAll();
        hintPanel.add(stopButton);
        revalidate();
        repaint();
    }

    /** Hides the stop button and restores the keyboard hint. */
    void hideStop() {
        hintPanel.removeAll();
        JLabel hintLabel = new JLabel("Enter to send · Shift+Enter for newline");
        hintLabel.setForeground(ThemeColors.secondaryText());
        hintLabel.setFont(hintLabel.getFont().deriveFont(hintLabel.getFont().getSize2D() - 2f));
        hintPanel.add(hintLabel);
        revalidate();
        repaint();
    }

    /** True while the stop button is showing (for tests). */
    boolean isStopShowing() {
        return hintPanel.getComponentCount() == 1 && hintPanel.getComponent(0) instanceof StopButton;
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
