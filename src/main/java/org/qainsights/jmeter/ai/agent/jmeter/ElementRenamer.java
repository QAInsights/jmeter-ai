package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "rename an element". Production code bridges the live
 * {@code GuiPackage} tree model and delegates to {@link JMeterTreeMutator};
 * tests supply an in-memory fake. Keeps the rename-element tool logic
 * unit-testable without a running JMeter GUI.
 */
@FunctionalInterface
public interface ElementRenamer {

    /**
     * Renames {@code node} to {@code newName}.
     *
     * @param node    the node to rename
     * @param newName the new name; blank/empty is rejected
     * @return {@code true} on success, {@code false} if the rename failed
     */
    boolean rename(JMeterTreeNode node, String newName);
}
