package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayDeque;
import java.util.Deque;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Converts between a {@link JMeterTreeNode} and a stable, human-readable
 * tree-path id such as {@code "Test Plan/Thread Group/HTTP Request[2]"}.
 * <p>
 * Ids are derived from element names; when sibling nodes share a name a 1-based
 * {@code [n]} index disambiguates them. Ids are computed fresh on every call
 * (never cached), so they reflect the current tree after edits or reloads.
 * <p>
 * The absolute tree root is treated as the coordinate origin and is excluded
 * from ids: JMeter's live tree model root is an internal wrapper whose first
 * child is the real Test Plan (see {@code JMeterTreeModel.addSubTree}). Excluding
 * it yields clean ids like {@code "Test Plan/Thread Group"} instead of a doubled
 * {@code "Test Plan/Test Plan/Thread Group"}.
 * <p>
 * Limitation: element names containing the path separator ('/') are not escaped
 * and may produce ambiguous ids.
 */
public final class ElementIdResolver {

    /** Path separator between element-name segments. */
    public static final String SEPARATOR = "/";

    /**
     * Builds the canonical tree-path id for the given node.
     *
     * @return the id, or {@code null} if the node has no backing test element
     */
    public String idOf(JMeterTreeNode node) {
        if (node == null || node.getTestElement() == null) {
            return null;
        }
        Deque<String> parts = new ArrayDeque<>();
        JMeterTreeNode current = node;
        while (current != null && current.getTestElement() != null) {
            JMeterTreeNode parent = parentOf(current);
            if (parent == null) {
                break; // absolute root (JMeter's wrapper) is the origin, not part of the id
            }
            parts.addFirst(segment(current));
            current = parent;
        }
        return String.join(SEPARATOR, parts);
    }

    /**
     * Finds the descendant of {@code root} whose id equals {@code id}.
     *
     * @return the matching node, or {@code null} if none matches
     */
    public JMeterTreeNode resolve(JMeterTreeNode root, String id) {
        if (root == null || id == null || id.isEmpty()) {
            return null;
        }
        Deque<JMeterTreeNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JMeterTreeNode node = stack.pop();
            if (id.equals(idOf(node))) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                JMeterTreeNode child = childAt(node, i);
                if (child != null) {
                    stack.push(child);
                }
            }
        }
        return null;
    }

    /** Builds a single path segment, appending a [n] index only when needed. */
    private String segment(JMeterTreeNode node) {
        String name = safeName(node);
        JMeterTreeNode parent = parentOf(node);
        if (parent == null) {
            return name;
        }
        int total = 0;
        int index = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            JMeterTreeNode sibling = childAt(parent, i);
            if (sibling != null && name.equals(safeName(sibling))) {
                total++;
                if (sibling == node) {
                    index = total;
                }
            }
        }
        return total > 1 ? name + "[" + index + "]" : name;
    }

    private static String safeName(JMeterTreeNode node) {
        String name = node.getName();
        return name == null ? "" : name;
    }

    private static JMeterTreeNode parentOf(JMeterTreeNode node) {
        TreeNode parent = node.getParent();
        return parent instanceof JMeterTreeNode ? (JMeterTreeNode) parent : null;
    }

    private static JMeterTreeNode childAt(JMeterTreeNode parent, int index) {
        TreeNode child = parent.getChildAt(index);
        return child instanceof JMeterTreeNode ? (JMeterTreeNode) child : null;
    }
}
