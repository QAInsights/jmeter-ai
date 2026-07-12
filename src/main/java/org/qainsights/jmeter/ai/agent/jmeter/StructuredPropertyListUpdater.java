package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "replace a structured-list property on an existing element"
 * (HTTP headers, User Defined Variables/HTTP parameters, or HTTP
 * Authorization entries). Production code bridges the live {@code GuiPackage}
 * tree model and delegates to {@link JMeterTreeMutator#replaceStructuredPropertyList};
 * tests supply an in-memory fake. Keeping this behind an interface lets the
 * {@code set_structured_property_list} tool logic be unit-tested without a
 * running JMeter GUI.
 */
@FunctionalInterface
public interface StructuredPropertyListUpdater {

    /**
     * Replaces {@code property} on the element backing {@code node} with
     * {@code entries}, in full.
     *
     * @param node     the target tree node
     * @param property the JMeter property key (e.g. {@code HeaderManager.headers})
     * @param entries  the new full list of entries (empty clears it); each map's
     *                 expected keys depend on {@code property}
     * @return {@code true} on success, {@code false} if the update failed
     */
    boolean update(JMeterTreeNode node, String property, List<Map<String, String>> entries);
}
