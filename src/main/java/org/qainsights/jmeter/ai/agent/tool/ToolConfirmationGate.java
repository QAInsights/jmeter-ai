package org.qainsights.jmeter.ai.agent.tool;

import java.util.Map;

/**
 * Seam asked by {@link ToolExecutor} before running a tool that has been marked
 * as requiring confirmation (typically a destructive mutation such as
 * {@code delete_element} or {@code move_element}). Production code shows a
 * blocking Swing dialog; tests inject a fake that answers without a GUI.
 */
@FunctionalInterface
public interface ToolConfirmationGate {

    /**
     * Asks whether the given tool call should proceed.
     *
     * @param toolName  the tool about to run
     * @param arguments its resolved arguments
     * @return {@code true} to proceed, {@code false} to decline the call
     */
    boolean confirm(String toolName, Map<String, Object> arguments);
}
