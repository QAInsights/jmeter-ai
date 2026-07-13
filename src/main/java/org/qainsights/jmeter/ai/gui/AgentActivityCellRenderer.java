package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.tree.TreeCellRenderer;

/**
 * Decorates an existing {@link TreeCellRenderer} (JMeter's own tree renderer) with
 * the agent-activity glow: rows whose backing value equals the current "active"
 * target (as reported by {@code activeTarget}) get {@link AgentGlowBorder}'s
 * rotating gradient stroke; every other row is unaffected other than a small,
 * always-reserved border inset (see {@link AgentGlowBorder}) that keeps row sizing
 * stable whether or not the glow is currently on for it.
 * <p>
 * Delegates everything else (icon, text, selection styling) to the wrapped
 * renderer, so this has no dependency on JMeter's actual renderer implementation.
 */
final class AgentActivityCellRenderer implements TreeCellRenderer {

    private final TreeCellRenderer delegate;
    private final Supplier<Object> activeTarget;
    private final AgentGlowBorder glowingBorder;
    private final AgentGlowBorder quietBorder;

    AgentActivityCellRenderer(TreeCellRenderer delegate, Supplier<Object> activeTarget,
                               IntSupplier rotationAngleDegrees, Supplier<Float> alpha) {
        this.delegate = delegate;
        this.activeTarget = activeTarget;
        this.glowingBorder = new AgentGlowBorder(rotationAngleDegrees, alpha, true);
        this.quietBorder = new AgentGlowBorder(rotationAngleDegrees, alpha, false);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                   boolean leaf, int row, boolean hasFocus) {
        Component rendered = delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row,
                hasFocus);
        if (rendered instanceof JComponent) {
            boolean active = value != null && value.equals(activeTarget.get());
            ((JComponent) rendered).setBorder(active ? glowingBorder : quietBorder);
        }
        return rendered;
    }
}
