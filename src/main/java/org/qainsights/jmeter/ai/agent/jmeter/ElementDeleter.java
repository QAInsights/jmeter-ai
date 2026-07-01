package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "delete an element from the tree". Production code bridges the live
 * {@code GuiPackage} tree model and delegates to {@link JMeterTreeMutator}; tests
 * supply an in-memory fake. Keeping this behind an interface lets the
 * delete-element tool logic be unit-tested without a running JMeter GUI.
 */
@FunctionalInterface
public interface ElementDeleter {

    /**
     * Removes {@code node} from the tree.
     *
     * @param node  the node to delete
     * @param force if {@code true}, allows deleting a node that still has children
     * @return {@code true} on success, {@code false} if the delete failed or was
     *         rejected by a guard
     */
    boolean delete(JMeterTreeNode node, boolean force);
}
