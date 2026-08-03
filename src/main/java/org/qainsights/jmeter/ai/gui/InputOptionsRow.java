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

        JLabel hintLabel = new JLabel("Enter to send · Shift+Enter for newline");
        hintLabel.setForeground(ThemeColors.secondaryText());
        hintLabel.setFont(hintLabel.getFont().deriveFont(hintLabel.getFont().getSize2D() - 2f));
        JPanel hintPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        hintPanel.setOpaque(false);
        hintPanel.add(hintLabel);
        add(hintPanel, BorderLayout.EAST);
    }

    /** Adds another input-adjacent option button next to the paperclip. */
    void addOption(JComponent component) {
        optionsStrip.add(component);
        revalidate();
    }

    /** Number of option buttons currently in the strip (for tests). */
    int getOptionCount() {
        return optionsStrip.getComponentCount();
    }
}
