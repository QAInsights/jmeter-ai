package org.qainsights.jmeter.ai.agent.jmeter;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Resolves a tool call's {@code element_id} argument to the {@link JMeterTreeNode}
 * the agent-activity tree glow should highlight, per the fallback rule chosen for
 * that feature: tools without an {@code element_id} (e.g. {@code run_test},
 * {@code save_plan}, {@code get_tree_state}) glow the Test Plan node itself rather
 * than nothing.
 * <p>
 * Pure resolution logic over an already-obtained tree root - no {@code GuiPackage}
 * dependency - so it is testable with plain constructed {@link JMeterTreeNode}s.
 */
public final class AgentTargetResolver {

    private final ElementIdResolver idResolver;

    public AgentTargetResolver() {
        this(new ElementIdResolver());
    }

    public AgentTargetResolver(ElementIdResolver idResolver) {
        this.idResolver = idResolver;
    }

    /**
     * @param absoluteRoot the live tree model's root (an internal JMeter wrapper node;
     *                      see {@link ElementIdResolver}'s note that its first child is
     *                      the actual visible "Test Plan" node)
     * @param elementId     a tool call's {@code element_id} argument; may be {@code null}
     *                      or blank
     * @return the resolved node, the Test Plan node as a fallback (either because
     *         {@code elementId} was absent, or didn't resolve to anything current), or
     *         {@code null} if {@code absoluteRoot} itself has no such fallback available
     */
    public JMeterTreeNode resolve(JMeterTreeNode absoluteRoot, String elementId) {
        if (absoluteRoot == null) {
            return null;
        }
        if (elementId == null || elementId.trim().isEmpty()) {
            return testPlanNode(absoluteRoot);
        }
        JMeterTreeNode resolved = idResolver.resolve(absoluteRoot, elementId);
        return resolved != null ? resolved : testPlanNode(absoluteRoot);
    }

    private JMeterTreeNode testPlanNode(JMeterTreeNode absoluteRoot) {
        for (int i = 0; i < absoluteRoot.getChildCount(); i++) {
            TreeNode child = absoluteRoot.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                return (JMeterTreeNode) child;
            }
        }
        return null;
    }
}
