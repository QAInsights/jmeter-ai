package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "duplicate an element's subtree as a new sibling". Production code
 * bridges the live {@code GuiPackage} tree model and delegates to
 * {@link JMeterTreeMutator}; tests supply an in-memory fake. Keeps the
 * duplicate-element tool logic unit-testable without a running JMeter GUI.
 */
@FunctionalInterface
public interface ElementDuplicator {

    /**
     * Duplicates {@code node}'s subtree and inserts the clone as the next
     * sibling immediately after it, under the same parent.
     *
     * @param node the node to duplicate
     * @return the newly created node, or {@code null} if the duplicate failed
     *         (e.g. no live JMeter GUI available, or the root was targeted)
     */
    JMeterTreeNode duplicate(JMeterTreeNode node);
}
