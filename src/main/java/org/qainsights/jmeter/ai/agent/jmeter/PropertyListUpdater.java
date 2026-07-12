package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.List;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "replace a flat string-list property on an existing element".
 * Production code bridges the live {@code GuiPackage} tree model and delegates
 * to {@link JMeterTreeMutator#replacePropertyList}; tests supply an in-memory
 * fake. Keeping this behind an interface lets the {@code set_property_list}
 * tool logic be unit-tested without a running JMeter GUI.
 */
@FunctionalInterface
public interface PropertyListUpdater {

    /**
     * Replaces {@code property} on the element backing {@code node} with
     * {@code values}, in full.
     *
     * @param node     the target tree node
     * @param property the JMeter property key (e.g. {@code Asserion.test_strings})
     * @param values   the new full list of values (empty clears it)
     * @return {@code true} on success, {@code false} if the update failed
     */
    boolean update(JMeterTreeNode node, String property, List<String> values);
}
