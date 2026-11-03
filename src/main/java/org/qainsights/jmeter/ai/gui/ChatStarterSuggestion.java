package org.qainsights.jmeter.ai.gui;

import java.util.function.Predicate;
import java.util.List;
import java.util.ArrayList;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.AbstractThreadGroup;

/** A local, read-only starter derived from the open test plan. */
record ChatStarterSuggestion(String label, String prompt) {

    static ChatStarterSuggestion fromTree(JMeterTreeNode root, JMeterTreeNode selected) {
        return optionsFromTree(root, selected).get(0);
    }

    static List<ChatStarterSuggestion> optionsFromTree(JMeterTreeNode root, JMeterTreeNode selected) {
        JMeterTreeNode group = findGroup(root, selected);
        ChatStarterSuggestion primary = primary(root, group);
        List<ChatStarterSuggestion> options = new ArrayList<>();
        options.add(primary);
        if (group != null && enabledInPlan(group) && group.getRoot() == root.getRoot()) {
            String target = "the Thread Group \"" + group.getName() + "\"";
            ChatStarterSuggestion review = reviewGroup(target);
            if (!primary.equals(review)) {
                options.add(review);
            } else {
                options.add(new ChatStarterSuggestion("Review response checks",
                        "@testplan Review the assertions in " + target
                                + " and suggest response checks for its samplers. Do not change the plan yet."));
            }
            options.add(new ChatStarterSuggestion("Plan the workload",
                    "How should I size the workload for " + target
                            + "? Ask me about the target rate, response time and duration before recommending settings."));
        } else {
            if (!primary.equals(reviewPlan())) {
                options.add(reviewPlan());
            } else {
                options.add(new ChatStarterSuggestion("Design a test scenario",
                        "How should I design a JMeter test scenario? Ask me about the protocol and user journey first."));
            }
            options.add(new ChatStarterSuggestion("Plan the workload",
                    "How should I plan a JMeter workload? Ask me about the target rate, response time and duration first."));
        }
        return List.copyOf(options);
    }

    private static JMeterTreeNode findGroup(JMeterTreeNode root, JMeterTreeNode selected) {
        if (root == null) {
            return null;
        }
        JMeterTreeNode group = selected;
        while (group != null && !(group.getTestElement() instanceof AbstractThreadGroup)) {
            group = group.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return group == null
                ? findEnabled(root, node -> node.getTestElement() instanceof AbstractThreadGroup) : group;
    }

    private static ChatStarterSuggestion primary(JMeterTreeNode root, JMeterTreeNode group) {
        if (root == null) {
            return reviewPlan();
        }
        if (group == null) {
            if (containsGroup(root)) {
                return reviewPlan();
            }
            return new ChatStarterSuggestion("Create a Thread Group",
                    "How should I configure a new Thread Group for this test plan? Ask me about the workload first.");
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
                    "How should I configure a CSV Data Set Config for " + target
                            + "? Ask me for the filename and variable names first.");
        }
        return reviewGroup(target);
    }

    private static ChatStarterSuggestion reviewGroup(String target) {
        return new ChatStarterSuggestion("Review this Thread Group",
                "@testplan Review " + target + " and suggest the next improvement. Do not change the plan yet.");
    }

    private static ChatStarterSuggestion reviewPlan() {
        return new ChatStarterSuggestion("Review this test plan",
                "@testplan Explain this test plan and suggest the next improvement. Do not change it yet.");
    }

    private static boolean hasCsvInScope(JMeterTreeNode group) {
        for (javax.swing.tree.TreeNode ancestor = group; ancestor instanceof JMeterTreeNode parent;
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
