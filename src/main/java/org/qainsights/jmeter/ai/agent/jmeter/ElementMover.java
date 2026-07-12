package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "move an element to a new parent". Production code bridges the live
 * {@code GuiPackage} tree model and delegates to {@link JMeterTreeMutator};
 * tests supply an in-memory fake. Keeps the move-element tool logic unit-testable
 * without a running JMeter GUI.
 */
@FunctionalInterface
public interface ElementMover {

    /**
     * Moves {@code node} to become the last child of {@code newParent}.
     *
     * @param node      the node to move
     * @param newParent the destination parent
     * @return {@code true} on success, {@code false} if the move was rejected
     *         (e.g. moving the root, or onto itself/a descendant)
     */
    boolean move(JMeterTreeNode node, JMeterTreeNode newParent);
}
