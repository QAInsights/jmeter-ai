package org.qainsights.jmeter.ai.agent.jmeter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.UIManager;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;
import org.qainsights.jmeter.ai.agent.tool.handlers.ReadToolHandlers;

/**
 * Production {@link ToolConfirmationGate}: builds a structured
 * {@link ConfirmationPreview} for the gated tool call on the calling
 * (background) thread, then blocks it while a modal Allow/Deny dialog renders
 * on the EDT via {@link EdtExecutor}. The dialog shows the preview as
 * label-value rows (sketch 001, variant B); unrecognized tools still get an
 * arg-only generic preview.
 */
public final class SwingToolConfirmationGate implements ToolConfirmationGate {

    /** Seam over the actual dialog call, for testing without a real GUI. */
    @FunctionalInterface
    public interface ConfirmDialog {
        boolean confirm(ConfirmationPreview.Preview preview);
    }

    private static final Color WARN_COLOR = new Color(0xB0, 0x86, 0x0B);
    private static final Color DANGER_COLOR = new Color(0xB0, 0x48, 0x3E);

    private final EdtExecutor edt;
    private final ConfirmDialog dialog;
    private final Supplier<JMeterTreeNode> rootSupplier;
    private final ConfirmationPreview previewBuilder;

    public SwingToolConfirmationGate() {
        this(EdtExecutor.swing(), SwingToolConfirmationGate::showDialog,
                ReadToolHandlers.guiPackageTree()::getRoot, new ConfirmationPreview());
    }

    public SwingToolConfirmationGate(EdtExecutor edt, ConfirmDialog dialog) {
        this(edt, dialog, () -> null, new ConfirmationPreview());
    }

    public SwingToolConfirmationGate(EdtExecutor edt, ConfirmDialog dialog,
            Supplier<JMeterTreeNode> rootSupplier, ConfirmationPreview previewBuilder) {
        this.edt = edt == null ? EdtExecutor.swing() : edt;
        this.dialog = dialog;
        this.rootSupplier = rootSupplier == null ? () -> null : rootSupplier;
        this.previewBuilder = previewBuilder == null ? new ConfirmationPreview() : previewBuilder;
    }

    @Override
    public boolean confirm(String toolName, Map<String, Object> arguments) {
        boolean[] result = {false};
        // Preview built AND dialog shown inside the EDT block: Swing tree
        // models are not thread-safe, and the user may keep editing the tree
        // until the modal opens - so reads happen on the EDT too (the read is
        // quick; the modal blocks the agent thread anyway).
        edt.run(() -> {
            ConfirmationPreview.Preview preview =
                    previewBuilder.describe(toolName, arguments, rootSupplier.get());
            result[0] = dialog.confirm(preview);
        });
        return result[0];
    }

    private static boolean showDialog(ConfirmationPreview.Preview preview) {
        Object[] options = {"Allow", "Deny"};
        int choice = JOptionPane.showOptionDialog(null, buildConfirmPanel(preview),
                "Confirm AI Agent Action", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return choice == JOptionPane.YES_OPTION;
    }

    /**
     * Builds the dialog body: bold request title, summary line, label-value
     * rows, and the severity-tinted note. Package-private for testing.
     */
    static JPanel buildConfirmPanel(ConfirmationPreview.Preview preview) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel title = new JLabel("The AI agent requests your approval");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        panel.add(title, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JLabel summary = new JLabel(preview.summary());
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        summary.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        content.add(summary);

        if (!preview.rows().isEmpty()) {
            content.add(rowsPanel(preview));
        }

        javax.swing.JTextArea note = wrappingText(preview.note(), 320);
        note.setForeground(switch (preview.level()) {
            case WARN -> WARN_COLOR;
            case DANGER -> DANGER_COLOR;
            default -> UIManager.getColor("Label.foreground");
        });
        note.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        content.add(note);

        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel rowsPanel(ConfirmationPreview.Preview preview) {
        JPanel rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        rows.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        rows.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        Font labelFont = UIManager.getFont("Label.font");
        Color labelColor = UIManager.getColor("Label.disabledForeground");
        int row = 0;
        for (ConfirmationPreview.Row r : preview.rows()) {
            GridBagConstraints left = new GridBagConstraints();
            left.gridx = 0;
            left.gridy = row;
            left.anchor = GridBagConstraints.NORTHWEST;
            left.insets = new Insets(1, 0, 1, 12);
            JLabel label = new JLabel(r.label());
            label.setFont(labelFont);
            label.setForeground(labelColor);
            rows.add(label, left);

            GridBagConstraints right = new GridBagConstraints();
            right.gridx = 1;
            right.gridy = row;
            right.weightx = 1;
            right.anchor = GridBagConstraints.NORTHWEST;
            right.fill = GridBagConstraints.HORIZONTAL;
            // Wrapping JTextArea (not HTML JLabel): probe-verified that Swing
            // HTML width hints (<body style>, <table width>) do not constrain
            // preferred size, so long paths would blow out the modal's width.
            rows.add(wrappingText(r.value(), 280), right);
            row++;
        }
        return rows;
    }

    /**
     * A read-only, transparent, word-wrapping text component pinned to the
     * given width - the old dialog's 360px-wrap behavior for the row values
     * and the note line. The {@code setSize} trick is what makes
     * preferred-height compute for the wrapped width.
     */
    private static javax.swing.JTextArea wrappingText(String text, int width) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        area.setSize(width, Short.MAX_VALUE);
        return area;
    }
}
