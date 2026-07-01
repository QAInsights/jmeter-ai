package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs structural mutations on the live JMeter tree (property updates,
 * enable/disable, move, delete) and fires the corresponding tree-model events
 * so the GUI refreshes. All mutations run on the EDT via an injected
 * {@link EdtExecutor}; tests pass {@link EdtExecutor#direct()}.
 * <p>
 * Each method returns {@code true} on success and {@code false} when a guard is
 * violated (null inputs, missing element, root operation, unsafe delete) or the
 * underlying JMeter call fails. Callers translate {@code false} into a
 * descriptive tool error for agent self-correction.
 */
public final class JMeterTreeMutator {

    private static final Logger log = LoggerFactory.getLogger(JMeterTreeMutator.class);

    private final EdtExecutor edt;

    public JMeterTreeMutator() {
        this(EdtExecutor.swing());
    }

    public JMeterTreeMutator(EdtExecutor edt) {
        this.edt = edt == null ? EdtExecutor.swing() : edt;
    }

    /** Sets a string property on the node's test element. */
    public boolean updateProperty(JMeterTreeModel model, JMeterTreeNode node, String property, String value) {
        if (!isValid(model, node) || property == null || property.trim().isEmpty()) {
            return false;
        }
        String safeValue = value == null ? "" : value;
        return mutate(() -> {
            node.getTestElement().setProperty(property, safeValue);
            model.nodeChanged(node);
            return true;
        });
    }

    /** Enables or disables the node and refreshes its display. */
    public boolean setEnabled(JMeterTreeModel model, JMeterTreeNode node, boolean enabled) {
        if (!isValid(model, node)) {
            return false;
        }
        return mutate(() -> {
            node.setEnabled(enabled);
            model.nodeChanged(node);
            return true;
        });
    }

    /**
     * Deletes the node. A node with children is only removed when
     * {@code force} is {@code true}, preventing accidental subtree deletion.
     * The tree root cannot be deleted.
     */
    public boolean deleteElement(JMeterTreeModel model, JMeterTreeNode node, boolean force) {
        if (!isValid(model, node) || node.getParent() == null) {
            return false;
        }
        if (node.getChildCount() > 0 && !force) {
            return false;
        }
        return mutate(() -> {
            model.removeNodeFromParent(node);
            return true;
        });
    }

    /**
     * Moves the node to become the last child of {@code newParent}. The root
     * cannot be moved, and a node cannot be moved under itself or a descendant.
     */
    public boolean moveElement(JMeterTreeModel model, JMeterTreeNode node, JMeterTreeNode newParent) {
        if (!isValid(model, node) || newParent == null || node.getParent() == null) {
            return false;
        }
        if (node == newParent || isAncestor(node, newParent)) {
            return false;
        }
        return mutate(() -> {
            model.removeNodeFromParent(node);
            model.insertNodeInto(node, newParent, newParent.getChildCount());
            return true;
        });
    }

    private boolean isValid(JMeterTreeModel model, JMeterTreeNode node) {
        return model != null && node != null && node.getTestElement() != null;
    }

    /** True if {@code maybeDescendant} is {@code node} itself or below it. */
    private boolean isAncestor(JMeterTreeNode node, JMeterTreeNode maybeDescendant) {
        TreeNode current = maybeDescendant;
        while (current != null) {
            if (current == node) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Runs the mutation on the EDT, capturing its result and swallowing failures. */
    private boolean mutate(Mutation mutation) {
        AtomicBoolean result = new AtomicBoolean(false);
        edt.run(() -> {
            try {
                result.set(mutation.apply());
            } catch (RuntimeException e) {
                log.error("Tree mutation failed", e);
                result.set(false);
            }
        });
        return result.get();
    }

    /** A tree mutation that returns whether it succeeded. */
    @FunctionalInterface
    private interface Mutation {
        boolean apply();
    }
}
