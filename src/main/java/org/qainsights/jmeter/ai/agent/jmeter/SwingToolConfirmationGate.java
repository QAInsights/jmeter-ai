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
        int choice = JOptionPane.showConfirmDialog(null, message, "Confirm AI agent action",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
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
