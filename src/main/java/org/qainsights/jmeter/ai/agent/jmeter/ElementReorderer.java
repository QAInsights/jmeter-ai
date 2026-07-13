package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "reposition an element among its current siblings, without
 * changing its parent". Production code bridges the live {@code GuiPackage}
 * tree model and delegates to {@link JMeterTreeMutator}; tests supply an
 * in-memory fake. Keeps the reorder-element tool logic unit-testable without
 * a running JMeter GUI.
 */
@FunctionalInterface
public interface ElementReorderer {

    /**
     * Moves {@code node} to position {@code newIndex} (0-based) among its
     * current siblings.
     *
     * @param node     the node to reposition
     * @param newIndex the desired 0-based position among {@code node}'s
     *                 current siblings
     * @return {@code true} on success, {@code false} if the reorder was
     *         rejected (e.g. reordering the root, or an out-of-range index)
     */
    boolean reorder(JMeterTreeNode node, int newIndex);
}
