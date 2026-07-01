package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "set a property on an existing element". Production code bridges the
 * live {@code GuiPackage} tree model and delegates to {@link JMeterTreeMutator};
 * tests supply an in-memory fake. Keeping this behind an interface lets the
 * update-property tool logic be unit-tested without a running JMeter GUI.
 */
@FunctionalInterface
public interface PropertyUpdater {

    /**
     * Sets {@code property} to {@code value} on the element backing {@code node}.
     *
     * @param node     the target tree node
     * @param property the JMeter property key (e.g. {@code HTTPSampler.path})
     * @param value    the new value (empty string clears it)
     * @return {@code true} on success, {@code false} if the update failed
     */
    boolean update(JMeterTreeNode node, String property, String value);
}
