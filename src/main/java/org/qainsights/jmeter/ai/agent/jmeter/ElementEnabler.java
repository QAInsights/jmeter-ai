package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "enable or disable an element". Production code bridges the live
 * {@code GuiPackage} tree model and delegates to {@link JMeterTreeMutator};
 * tests supply an in-memory fake. Keeps the toggle-element tool logic unit-
 * testable without a running JMeter GUI.
 */
@FunctionalInterface
public interface ElementEnabler {

    /**
     * Enables or disables {@code node}.
     *
     * @param node    the node to toggle
     * @param enabled {@code true} to enable, {@code false} to disable
     * @return {@code true} on success, {@code false} if the change failed
     */
    boolean setEnabled(JMeterTreeNode node, boolean enabled);
}
