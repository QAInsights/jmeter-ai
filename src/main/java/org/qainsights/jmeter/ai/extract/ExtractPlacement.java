package org.qainsights.jmeter.ai.extract;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds where an extractor goes in the plan and where the extracted value is still hard-coded. */
public final class ExtractPlacement {

    /** Values shorter than this are too likely to appear by accident to replace. */
    static final int MIN_REPLACE_LENGTH = 4;

    private static final Set<String> SKIPPED = new HashSet<>(List.of(
            TestElement.NAME, TestElement.GUI_CLASS, TestElement.TEST_CLASS, TestElement.ENABLED,
            TestElement.COMMENTS));

    private ExtractPlacement() {
    }

    /**
     * Samplers that could have produced a result labelled {@code label}; redirect sub-results are
     * labelled "Name-0". More than one entry means the label is ambiguous and the user must choose.
     */
    public static List<JMeterTreeNode> findSamplers(JMeterTreeNode root, String label) {
        if (root == null || label == null) {
            return Collections.emptyList();
        }
        List<JMeterTreeNode> exact = samplersNamed(root, label);
        if (!exact.isEmpty()) {
            return exact;
        }
        String stripped = label.replaceFirst("-\\d+$", "");
        return stripped.equals(label) ? Collections.emptyList() : samplersNamed(root, stripped);
    }

    private static List<JMeterTreeNode> samplersNamed(JMeterTreeNode root, String name) {
        List<JMeterTreeNode> out = new ArrayList<>();
        for (JMeterTreeNode node : preorder(root)) {
            if (node.getTestElement() instanceof Sampler && name.equals(node.getName())) {
                out.add(node);
            }
        }
        return out;
    }

    /** "Users > Login" style path below the Test Plan, used to tell same-named samplers apart. */
    public static String pathOf(JMeterTreeNode node) {
        StringBuilder sb = new StringBuilder();
        for (javax.swing.tree.TreeNode n = node; n instanceof JMeterTreeNode; n = n.getParent()) {
            JMeterTreeNode j = (JMeterTreeNode) n;
            if (j.getTestElement() instanceof org.apache.jmeter.testelement.TestPlan || j.getParent() == null) {
                break;
            }
            sb.insert(0, sb.length() == 0 ? j.getName() : j.getName() + " > ");
        }
        return sb.toString();
    }

    /**
     * Elements after {@code source} (and outside its subtree) in the same Thread Group that still
     * contain {@code value}. Variables are per thread, so other Thread Groups never see the extracted value.
     */
    public static List<JMeterTreeNode> laterUses(JMeterTreeNode root, JMeterTreeNode source, String value) {
        if (root == null || source == null || value == null || value.length() < MIN_REPLACE_LENGTH) {
            return Collections.emptyList();
        }
        JMeterTreeNode group = threadGroupOf(source);
        if (group == null) {
            return Collections.emptyList();
        }
        List<JMeterTreeNode> all = preorder(group);
        int start = all.indexOf(source);
        if (start < 0) {
            return Collections.emptyList();
        }
        int after = start + 1 + preorder(source).size() - 1;
        List<JMeterTreeNode> out = new ArrayList<>();
        for (int i = after; i < all.size(); i++) {
            JMeterTreeNode node = all.get(i);
            if (node.isEnabled() && contains(node.getTestElement(), value)) {
                out.add(node);
            }
        }
        return out;
    }

    private static JMeterTreeNode threadGroupOf(JMeterTreeNode node) {
        for (javax.swing.tree.TreeNode n = node.getParent(); n instanceof JMeterTreeNode; n = n.getParent()) {
            if (((JMeterTreeNode) n).getTestElement() instanceof AbstractThreadGroup) {
                return (JMeterTreeNode) n;
            }
        }
        return null;
    }

    /** Replaces {@code value} with {@code ${var}} in each node; returns how many properties changed. */
    public static int replace(List<JMeterTreeNode> nodes, String value, String var) {
        int changed = 0;
        for (JMeterTreeNode node : nodes) {
            changed += replaceIn(node.getTestElement().propertyIterator(), value, "${" + var + "}");
        }
        return changed;
    }

    static boolean contains(TestElement element, String value) {
        return element != null && containsIn(element.propertyIterator(), value);
    }

    private static boolean containsIn(PropertyIterator it, String value) {
        while (it.hasNext()) {
            JMeterProperty p = it.next();
            if (SKIPPED.contains(p.getName())) {
                continue;
            }
            if (p instanceof MultiProperty) {
                if (containsIn(((MultiProperty) p).iterator(), value)) {
                    return true;
                }
            } else if (p instanceof StringProperty && p.getStringValue().contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static int replaceIn(PropertyIterator it, String value, String replacement) {
        int changed = 0;
        while (it.hasNext()) {
            JMeterProperty p = it.next();
            if (SKIPPED.contains(p.getName())) {
                continue;
            }
            if (p instanceof MultiProperty) {
                changed += replaceIn(((MultiProperty) p).iterator(), value, replacement);
            } else if (p instanceof StringProperty && p.getStringValue().contains(value)) {
                p.setObjectValue(p.getStringValue().replace(value, replacement));
                changed++;
            }
        }
        return changed;
    }

    private static List<JMeterTreeNode> preorder(JMeterTreeNode root) {
        List<JMeterTreeNode> out = new ArrayList<>();
        Enumeration<?> e = root.preorderEnumeration();
        while (e.hasMoreElements()) {
            Object n = e.nextElement();
            if (n instanceof JMeterTreeNode) {
                out.add((JMeterTreeNode) n);
            }
        }
        return out;
    }
}
