package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.Map;

import javax.swing.JOptionPane;

import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;

/**
 * Production {@link ToolConfirmationGate}: blocks the calling (background)
 * thread and shows a modal Yes/No dialog on the EDT via {@link EdtExecutor}.
 * The message is built generically from common argument keys
 * ({@code element_id}, {@code new_parent_id}, {@code force}) so it covers any
 * gated tool without needing tool-specific wiring here.
 */
public final class SwingToolConfirmationGate implements ToolConfirmationGate {

    /** Seam over the actual dialog call, for testing without a real GUI. */
    @FunctionalInterface
    public interface ConfirmDialog {
        boolean confirm(String message);
    }

    private final EdtExecutor edt;
    private final ConfirmDialog dialog;

    public SwingToolConfirmationGate() {
        this(EdtExecutor.swing(), SwingToolConfirmationGate::showDialog);
    }

    public SwingToolConfirmationGate(EdtExecutor edt, ConfirmDialog dialog) {
        this.edt = edt == null ? EdtExecutor.swing() : edt;
        this.dialog = dialog;
    }

    @Override
    public boolean confirm(String toolName, Map<String, Object> arguments) {
        String message = describe(toolName, arguments);
        boolean[] result = {false};
        edt.run(() -> result[0] = dialog.confirm(message));
        return result[0];
    }

    private static boolean showDialog(String message) {
        Object[] options = {"Allow", "Deny"};
        int choice = JOptionPane.showOptionDialog(null, buildConfirmPanel(message),
                "Confirm AI Agent Action", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return choice == JOptionPane.YES_OPTION;
    }

    /**
     * Builds the structured dialog body: a bold action summary above the
     * wrapped detail text. Package-private for testing.
     */
    static javax.swing.JPanel buildConfirmPanel(String message) {
        javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 8));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4));

        javax.swing.JLabel title = new javax.swing.JLabel("The AI agent requests your approval");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(title, java.awt.BorderLayout.NORTH);

        javax.swing.JTextArea detail = new javax.swing.JTextArea(message);
        detail.setEditable(false);
        detail.setOpaque(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setFont(javax.swing.UIManager.getFont("Label.font"));
        detail.setSize(new java.awt.Dimension(360, Short.MAX_VALUE));
        panel.add(detail, java.awt.BorderLayout.CENTER);
        return panel;
    }

    private static String describe(String toolName, Map<String, Object> arguments) {
        StringBuilder sb = new StringBuilder("The AI agent wants to run '").append(toolName).append('\'');
        Object elementId = arguments.get("element_id");
        if (elementId != null) {
            sb.append(" on '").append(elementId).append('\'');
        }
        Object newParentId = arguments.get("new_parent_id");
        if (newParentId != null) {
            sb.append(", moving it under '").append(newParentId).append('\'');
        }
        if (Boolean.parseBoolean(String.valueOf(arguments.get("force")))) {
            sb.append(" (including all of its children)");
        }
        sb.append(".\n\nAllow this action?");
        return sb.toString();
    }
}
