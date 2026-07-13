package org.qainsights.jmeter.ai.agent.jmeter;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;

/**
 * Renders a JMeter tree into a compact, depth-limited text snapshot annotated
 * with stable element ids (from {@link ElementIdResolver}). Pure and GUI-free so
 * it is easy to unit test; the caller supplies the root node.
 */
public final class TreeStateFormatter {

    private final ElementIdResolver resolver;

    public TreeStateFormatter(ElementIdResolver resolver) {
        this.resolver = resolver == null ? new ElementIdResolver() : resolver;
    }

    /**
     * Formats the tree rooted at {@code root}.
     * <p>
     * The {@code root} node itself is treated as the coordinate origin and is not
     * rendered; its children are rendered starting at depth 0. This matches the
     * live JMeter tree, whose model root is an internal wrapper above the real
     * Test Plan, so the Test Plan renders at depth 0 with id {@code "Test Plan"}.
     *
     * @param root     the tree root/origin (not rendered)
     * @param maxDepth deepest level to render (root's children = 0); negative means unlimited
     * @return the rendered snapshot, or an empty string if {@code root} is null
     */
    public String format(JMeterTreeNode root, int maxDepth) {
        if (root == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendChildren(root, sb, 0, maxDepth);
        return sb.toString();
    }

    private void append(JMeterTreeNode node, StringBuilder sb, int depth, int maxDepth) {
        TestElement element = node.getTestElement();
        if (element == null) {
            // Hidden root: descend into children without consuming a depth level.
            appendChildren(node, sb, depth, maxDepth);
            return;
        }

        String indent = indent(depth);
        sb.append(indent).append('[').append(element.getClass().getSimpleName()).append("] ")
                .append(node.getName());
        if (!node.isEnabled()) {
            sb.append(" [disabled]");
        }
        sb.append(" (id=").append(resolver.idOf(node)).append(")\n");

        int childCount = node.getChildCount();
        if (maxDepth >= 0 && depth >= maxDepth) {
            if (childCount > 0) {
                sb.append(indent).append("  ... (").append(childCount)
                        .append(" child(ren) hidden; increase depth)\n");
            }
            return;
        }
        appendChildren(node, sb, depth + 1, maxDepth);
    }

    private void appendChildren(JMeterTreeNode node, StringBuilder sb, int childDepth, int maxDepth) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                append((JMeterTreeNode) child, sb, childDepth, maxDepth);
            }
        }
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }
}
