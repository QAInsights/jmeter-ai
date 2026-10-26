package org.qainsights.jmeter.ai.load;

import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.PostThreadGroup;
import org.apache.jmeter.threads.SetupThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;

/** Resolves the Thread Group and sampler count for an {@code @load} dialog. */
public final class LoadCommandHandler {
    public static final String NO_GUI = "The JMeter GUI is not available, so I can't design a workload.";
    public static final String NO_THREAD_GROUP = "Add a Thread Group with requests first, then run `@load` again.";
    public static final String NO_REQUESTS = "Add requests to the Thread Group first.";

    private LoadCommandHandler() {
    }

    public static JMeterTreeNode selectedNode() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null || gui.getTreeListener() == null ? null : gui.getTreeListener().getCurrentNode();
    }

    public static Preparation prepare(String message, JMeterTreeNode selected, JMeterTreeModel model) {
        if (model == null) {
            return Preparation.error(NO_GUI);
        }
        List<JMeterTreeNode> groups = eligibleThreadGroups(model);
        if (groups.isEmpty()) {
            return Preparation.error(NO_THREAD_GROUP);
        }
        JMeterTreeNode selectedGroup = containingGroup(selected, groups);
        JMeterTreeNode defaultGroup = selectedGroup == null ? groups.get(0) : selectedGroup;
        int count = WorkloadApplier.countEnabledSamplers(defaultGroup);
        if (count == 0) {
            return Preparation.error(NO_REQUESTS);
        }
        return new Preparation(null, WorkloadParser.parse(message), groups, defaultGroup,
                groups.size(), model, testPlanFileName());
    }

    public static List<JMeterTreeNode> eligibleThreadGroups(JMeterTreeModel model) {
        List<JMeterTreeNode> groups = new ArrayList<>();
        if (model != null) {
            collectGroups((JMeterTreeNode) model.getRoot(), groups);
        }
        return List.copyOf(groups);
    }

    public static String prefixFor(JMeterTreeNode node, int eligibleCount) {
        return WorkloadApplier.defaultPrefix(node, eligibleCount);
    }

    private static void collectGroups(JMeterTreeNode node, List<JMeterTreeNode> groups) {
        if (node.isEnabled() && node.getTestElement() instanceof ThreadGroup
                && !(node.getTestElement() instanceof SetupThreadGroup)
                && !(node.getTestElement() instanceof PostThreadGroup)) {
            groups.add(node);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectGroups((JMeterTreeNode) node.getChildAt(index), groups);
        }
    }

    private static JMeterTreeNode containingGroup(JMeterTreeNode node, List<JMeterTreeNode> groups) {
        for (JMeterTreeNode current = node; current != null; current = (JMeterTreeNode) current.getParent()) {
            if (groups.contains(current)) {
                return current;
            }
        }
        return null;
    }

    private static String testPlanFileName() {
        GuiPackage gui = GuiPackage.getInstance();
        String path = gui == null ? null : gui.getTestPlanFile();
        if (path == null || path.isBlank()) {
            return "plan.jmx";
        }
        return java.nio.file.Path.of(path).getFileName().toString();
    }

    public record Preparation(String error, WorkloadSpec spec, List<JMeterTreeNode> threadGroups,
                              JMeterTreeNode selectedGroup, int eligibleCount, JMeterTreeModel model,
                              String planFileName) {
        public Preparation {
            threadGroups = threadGroups == null ? List.of() : List.copyOf(threadGroups);
        }

        private static Preparation error(String message) {
            return new Preparation(message, null, List.of(), null, 0, null, "plan.jmx");
        }
    }
}
