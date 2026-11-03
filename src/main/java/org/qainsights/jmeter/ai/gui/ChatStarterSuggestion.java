package org.qainsights.jmeter.ai.gui;

import java.util.function.Predicate;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.AbstractThreadGroup;

/** A local, read-only starter derived from the open test plan. */
record ChatStarterSuggestion(String label, String prompt) {

    static ChatStarterSuggestion fromTree(JMeterTreeNode root, JMeterTreeNode selected) {
        if (root == null) {
            return reviewPlan();
        }
        JMeterTreeNode group = selected;
        while (group != null && !(group.getTestElement() instanceof AbstractThreadGroup)) {
            group = group.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        if (group == null) {
            group = findEnabled(root, node -> node.getTestElement() instanceof AbstractThreadGroup);
        }
        if (group == null) {
            if (containsGroup(root)) {
                return reviewPlan();
            }
            return new ChatStarterSuggestion("Create a Thread Group",
                    "Add a Thread Group to this test plan. Ask me about the workload before configuring it.");
        }
        if (!enabledInPlan(group) || group.getRoot() != root.getRoot()) {
            return reviewPlan();
        }
        String target = "the Thread Group \"" + group.getName() + "\"";
        if (findEnabled(group, node -> node.getTestElement() instanceof Sampler) == null) {
            return new ChatStarterSuggestion("Add a request",
                    "Help me add a sampler to " + target + ". Ask me which protocol and endpoint to use.");
        }
        if (!hasCsvInScope(group)) {
            return new ChatStarterSuggestion("Add a CSV data set",
                    "Add a CSV Data Set Config to " + target
                            + ". Ask me for the filename and variable names before configuring it.");
        }
        return new ChatStarterSuggestion("Review this Thread Group",
                "@testplan Review " + target + " and suggest the next improvement. Do not change the plan yet.");
    }

    private static ChatStarterSuggestion reviewPlan() {
        return new ChatStarterSuggestion("Review this test plan",
                "@testplan Explain this test plan and suggest the next improvement. Do not change it yet.");
    }

    private static boolean hasCsvInScope(JMeterTreeNode group) {
        if (findEnabled(group, node -> node.getTestElement() instanceof CSVDataSet) != null) {
            return true;
        }
        for (var ancestor = group.getParent(); ancestor instanceof JMeterTreeNode parent;
                ancestor = parent.getParent()) {
            for (int index = 0; index < parent.getChildCount(); index++) {
                JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
                if (child.isEnabled() && child.getTestElement() instanceof CSVDataSet) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JMeterTreeNode findEnabled(JMeterTreeNode node, Predicate<JMeterTreeNode> predicate) {
        if (!node.isEnabled()) {
            return null;
        }
        if (predicate.test(node)) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            JMeterTreeNode found = findEnabled((JMeterTreeNode) node.getChildAt(index), predicate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean enabledInPlan(JMeterTreeNode node) {
        for (var current = node; current != null;
                current = current.getParent() instanceof JMeterTreeNode parent ? parent : null) {
            if (!current.isEnabled()) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsGroup(JMeterTreeNode node) {
        if (node.getTestElement() instanceof AbstractThreadGroup) {
            return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            if (containsGroup((JMeterTreeNode) node.getChildAt(index))) {
                return true;
            }
        }
        return false;
    }
}
