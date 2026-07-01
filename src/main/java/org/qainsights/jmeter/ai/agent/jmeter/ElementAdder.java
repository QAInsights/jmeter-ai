package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Seam over "create an element and attach it under a parent node". Production
 * code ({@link GuiElementAdder}) bridges the JMeter selection model and delegates
 * to {@code JMeterElementManager}; tests supply an in-memory fake. Keeping this
 * behind an interface lets the add-element tool logic be unit-tested without a
 * running JMeter GUI.
 */
@FunctionalInterface
public interface ElementAdder {

    /**
     * Adds a new element under {@code parent}.
     *
     * @param parent   the target parent node
     * @param addAlias the {@code JMeterElementManager} type alias to instantiate
     * @param name     the desired element name, or {@code null}/empty for the default
     * @return the newly created tree node, or {@code null} if the add failed
     */
    JMeterTreeNode add(JMeterTreeNode parent, String addAlias, String name);
}
