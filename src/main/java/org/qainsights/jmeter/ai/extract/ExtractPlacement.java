package org.qainsights.jmeter.ai.extract;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;

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

    /** The sampler that produced a result labelled {@code label}; redirect sub-results are labelled "Name-0". */
    public static JMeterTreeNode findSampler(JMeterTreeNode root, String label) {
        if (root == null || label == null) {
            return null;
        }
        JMeterTreeNode exact = findSamplerNamed(root, label);
        if (exact != null) {
            return exact;
        }
        String stripped = label.replaceFirst("-\\d+$", "");
        return stripped.equals(label) ? null : findSamplerNamed(root, stripped);
    }

    private static JMeterTreeNode findSamplerNamed(JMeterTreeNode root, String name) {
        for (JMeterTreeNode node : preorder(root)) {
            if (node.getTestElement() instanceof Sampler && name.equals(node.getName())) {
                return node;
            }
        }
        return null;
    }

    /** Elements after {@code source} (and outside its subtree) that still contain {@code value}. */
    public static List<JMeterTreeNode> laterUses(JMeterTreeNode root, JMeterTreeNode source, String value) {
        if (root == null || source == null || value == null || value.length() < MIN_REPLACE_LENGTH) {
            return Collections.emptyList();
        }
        List<JMeterTreeNode> all = preorder(root);
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
