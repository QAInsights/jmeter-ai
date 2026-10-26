package org.qainsights.jmeter.ai.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.timers.ConstantThroughputTimer;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies a calculated workload and retains the last edit for Ctrl+Z undo. */
public final class WorkloadApplier {
    static final String NOTHING_TO_UNDO = "Nothing to undo: the last `@load` changes are no longer in the plan.";
    static final String CTT_NAME = "Target throughput (Feather Wand)";
    static final String THINK_NAME = "Think time (Feather Wand)";
    static final String CTT_GUI = "org.apache.jmeter.testbeans.gui.TestBeanGUI";
    static final String TIMER_GUI = "org.apache.jmeter.timers.gui.ConstantTimerGui";
    static final String OPEN_MODEL_GUI = "org.apache.jmeter.threads.openmodel.gui.OpenModelThreadGroupGui";
    static final String OPEN_MODEL_SCHEDULE = "OpenModelThreadGroup.schedule";
    static final String OPEN_MODEL_MANAGED = "FeatherWand.openModelManaged";
    static final String OPEN_MODEL_SOURCE = "FeatherWand.openModelSource";
    // The zero-based mode index for all active threads in this Thread Group (shared).
    static final int CALC_MODE_THREAD_GROUP_SHARED = 4;
    private static final Logger log = LoggerFactory.getLogger(WorkloadApplier.class);
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static LastApplied lastApplied;

    private WorkloadApplier() {
    }

    private static ApplyResult applyOpenModel(JMeterTreeModel model, JMeterTreeNode selectedNode,
                                              WorkloadPlan plan, Runnable flushCurrentNode,
                                              GuiPackage gui, boolean currentGroup)
            throws IllegalUserActionException {
        List<PropertySnapshot> properties = new ArrayList<>();
        List<EnabledSnapshot> enabledStates = new ArrayList<>();
        List<JMeterTreeNode> added = new ArrayList<>();
        List<TreeMigration> migrations = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String prefix = plan.propertyPrefix();
        JMeterTreeNode openModelNode = selectedNode;
        try {
            if (selectedNode.getTestElement() instanceof ThreadGroup) {
                String convertedName = enabledManagedOpenModelSiblingName(selectedNode);
                if (convertedName != null) {
                    throw new IllegalArgumentException("This Thread Group was converted to \"" + convertedName
                            + "\". Select it to change the shape.");
                }
                JMeterTreeNode parent = (JMeterTreeNode) selectedNode.getParent();
                OpenModelThreadGroup openModel = new OpenModelThreadGroup();
                openModel.setName(selectedNode.getName() + " (" + plan.shapeConfig().shape() + ")");
                openModel.setProperty(TestElement.TEST_CLASS, OpenModelThreadGroup.class.getName());
                openModel.setProperty(TestElement.GUI_CLASS, OPEN_MODEL_GUI);
                openModel.setProperty(OPEN_MODEL_MANAGED, true);
                openModel.setProperty(OPEN_MODEL_SOURCE, selectedNode.getName());
                int childCount = selectedNode.getChildCount();
                List<JMeterTreeNode> children = new ArrayList<>(childCount);
                for (int index = 0; index < childCount; index++) {
                    children.add((JMeterTreeNode) selectedNode.getChildAt(index));
                }
                openModelNode = new JMeterTreeNode(openModel, model);
                model.insertNodeInto(openModelNode, parent, parent.getIndex(selectedNode) + 1);
                added.add(openModelNode);
                TreeMigration migration = new TreeMigration(selectedNode, openModelNode);
                migrations.add(migration);
                for (int index = 0; index < children.size(); index++) {
                    JMeterTreeNode child = children.get(index);
                    model.removeNodeFromParent(child);
                    model.insertNodeInto(child, openModelNode, index);
                }
                setEnabled(model, selectedNode, false, enabledStates, properties);
                changes.add("Added " + openModelNode.getName());
            } else if (!selectedNode.getTestElement().getPropertyAsBoolean(OPEN_MODEL_MANAGED)) {
                throw new IllegalArgumentException(
                        "Only Open Model Thread Groups created by Workload Designer can be updated.");
            }

            TestElement group = openModelNode.getTestElement();
            setProperty(properties, group, OPEN_MODEL_SCHEDULE,
                    new StringProperty(OPEN_MODEL_SCHEDULE, plan.openModelSchedule()), changes);
            JMeterTreeNode managedCtt = managedTimer(openModelNode, ConstantThroughputTimer.class, CTT_NAME);
            if (managedCtt != null && managedCtt.isEnabled()) {
                setEnabled(model, managedCtt, false, enabledStates, properties);
                changes.add("Disabled " + CTT_NAME);
            }
            if (hasUserOwnedThroughputTimerInScope(openModelNode, true)
                    || hasEnabledShapingTimer(openModelNode, true)) {
                warnings.add("A user-owned Constant Throughput Timer, Precise Throughput Timer or "
                        + "Throughput Shaping Timer can throttle arrivals; it was left unchanged.");
            }
            applyThinkTimer(model, openModelNode, plan, properties, enabledStates, added, changes);
            int existingTimers = otherTimerCount(openModelNode);
            if (existingTimers > 0) {
                warnings.add("Your plan already has " + existingTimers
                        + " timers. The estimate does not count them.");
            }
            model.nodeStructureChanged(openModelNode);
            model.nodeStructureChanged(selectedNode);
            refreshCurrentGui(gui, openModelNode, currentGroup);
            lastApplied = new LastApplied(selectedNode, properties, enabledStates, added, migrations);
            return new ApplyResult(openModelNode.getName(), changes, warnings);
        } catch (Exception error) {
            restore(model, selectedNode, properties, enabledStates, added, migrations,
                    gui, currentGroup, error);
            throw error;
        }
    }

    private static String enabledManagedOpenModelSiblingName(JMeterTreeNode source) {
        if (source.isEnabled() || source.getParent() == null) {
            return null;
        }
        JMeterTreeNode parent = (JMeterTreeNode) source.getParent();
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode sibling = (JMeterTreeNode) parent.getChildAt(index);
            if (sibling == source || !sibling.isEnabled()
                    || !(sibling.getTestElement() instanceof OpenModelThreadGroup group)
                    || !group.getPropertyAsBoolean(OPEN_MODEL_MANAGED)
                    || !source.getName().equals(group.getPropertyAsString(OPEN_MODEL_SOURCE))) {
                continue;
            }
            return sibling.getName();
        }
        return null;
    }

    private static void applyThinkTimer(JMeterTreeModel model, JMeterTreeNode group, WorkloadPlan plan,
                                        List<PropertySnapshot> properties, List<EnabledSnapshot> enabledStates,
                                        List<JMeterTreeNode> added, List<String> changes)
            throws IllegalUserActionException {
        JMeterTreeNode thinkNode = managedTimer(group, ConstantTimer.class, THINK_NAME);
        if (plan.thinkMillis() > 0) {
            if (thinkNode == null) {
                ConstantTimer timer = new ConstantTimer();
                timer.setName(THINK_NAME);
                timer.setProperty(TestElement.TEST_CLASS, ConstantTimer.class.getName());
                timer.setProperty(TestElement.GUI_CLASS, TIMER_GUI);
                thinkNode = model.addComponent(timer, group);
                added.add(thinkNode);
                changes.add("Added " + THINK_NAME);
            } else if (!thinkNode.isEnabled()) {
                setEnabled(model, thinkNode, true, enabledStates, properties);
            }
            ConstantTimer timer = (ConstantTimer) thinkNode.getTestElement();
            setProperty(properties, timer, ConstantTimer.DELAY,
                    new StringProperty(ConstantTimer.DELAY,
                            property(plan.propertyPrefix(), "think_ms", plan.thinkMillis())), changes);
            model.nodeChanged(thinkNode);
        } else if (thinkNode != null && thinkNode.isEnabled()) {
            setEnabled(model, thinkNode, false, enabledStates, properties);
        }
    }

    public static synchronized ApplyResult apply(JMeterTreeModel model, JMeterTreeNode threadGroupNode,
                                                WorkloadPlan plan) throws IllegalUserActionException {
        GuiPackage gui = GuiPackage.getInstance();
        boolean currentGroup = gui != null && gui.getCurrentNode() == threadGroupNode;
        return apply(model, threadGroupNode, plan,
                () -> {
                    if (currentGroup) {
                        gui.updateCurrentNode();
                    }
                }, gui, currentGroup);
    }

    static synchronized ApplyResult apply(JMeterTreeModel model, JMeterTreeNode threadGroupNode,
                                          WorkloadPlan plan, Runnable flushCurrentNode)
            throws IllegalUserActionException {
        return apply(model, threadGroupNode, plan, flushCurrentNode, null, false);
    }

    private static ApplyResult apply(JMeterTreeModel model, JMeterTreeNode threadGroupNode,
                                     WorkloadPlan plan, Runnable flushCurrentNode,
                                     GuiPackage gui, boolean currentGroup) throws IllegalUserActionException {
        if (model == null || threadGroupNode == null || threadGroupNode.getRoot() != model.getRoot()
                || !(threadGroupNode.getTestElement() instanceof ThreadGroup)
                && !(threadGroupNode.getTestElement() instanceof OpenModelThreadGroup)) {
            throw new IllegalArgumentException("The selected Thread Group is no longer in the test plan.");
        }
        if (plan == null || plan.threads() < 1 || plan.rampSeconds() < 0 || plan.durationSeconds() < 1
                || plan.thinkMillis() < 0 || plan.propertyPrefix() == null || plan.propertyPrefix().isBlank()) {
            throw new IllegalArgumentException("The workload plan is invalid.");
        }
        flushCurrentNode.run();
        if (plan.shapeConfig().shape().usesOpenModelThreadGroup()) {
            return applyOpenModel(model, threadGroupNode, plan, flushCurrentNode, gui, currentGroup);
        }
        if (!(threadGroupNode.getTestElement() instanceof ThreadGroup)) {
            throw new IllegalArgumentException("Load and Endurance need a standard Thread Group. "
                    + "Select the original group, or pick Step, Spike, Stress or Custom.");
        }
        ThreadGroup group = (ThreadGroup) threadGroupNode.getTestElement();
        if (!(group.getSamplerController() instanceof LoopController)) {
            throw new IllegalStateException("The Thread Group has no main Loop Controller.");
        }
        List<PropertySnapshot> properties = new ArrayList<>();
        List<EnabledSnapshot> enabledStates = new ArrayList<>();
        List<JMeterTreeNode> added = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String prefix = plan.propertyPrefix();
        try {
            setProperty(properties, group, ThreadGroup.NUM_THREADS,
                    new StringProperty(ThreadGroup.NUM_THREADS, property(prefix, "threads", plan.threads())),
                    changes);
            setProperty(properties, group, ThreadGroup.RAMP_TIME,
                    new StringProperty(ThreadGroup.RAMP_TIME, property(prefix, "rampup", plan.rampSeconds())),
                    changes);
            setProperty(properties, group, ThreadGroup.SCHEDULER,
                    new BooleanProperty(ThreadGroup.SCHEDULER, true), changes);
            setProperty(properties, group, ThreadGroup.DURATION,
                    new StringProperty(ThreadGroup.DURATION, property(prefix, "duration", plan.durationSeconds())),
                    changes);
            setProperty(properties,
                    () -> (TestElement) ((ThreadGroup) threadGroupNode.getTestElement()).getSamplerController(),
                    LoopController.LOOPS,
                    new IntegerProperty(LoopController.LOOPS, LoopController.INFINITE_LOOP_COUNT), changes);

            if (plan.rpm() != null) {
                if (containsShapingTimer(threadGroupNode)) {
                    warnings.add("A Precise Throughput Timer or Throughput Shaping Timer already controls rate; "
                            + "no Constant Throughput Timer was changed.");
                } else if (hasEnabledNestedThroughputTimer(threadGroupNode)) {
                    warnings.add("A Constant Throughput Timer nested under a request or controller already affects part of this "
                            + "Thread Group; no group-level Constant Throughput Timer was added or changed.");
                } else {
                    JMeterTreeNode timerNode = enabledDirectChild(threadGroupNode, ConstantThroughputTimer.class);
                    if (timerNode == null) {
                        timerNode = managedTimer(threadGroupNode, ConstantThroughputTimer.class, CTT_NAME);
                    }
                    if (timerNode == null) {
                        ConstantThroughputTimer timer = new ConstantThroughputTimer();
                        timer.setName(CTT_NAME);
                        timer.setProperty(TestElement.TEST_CLASS, ConstantThroughputTimer.class.getName());
                        timer.setProperty(TestElement.GUI_CLASS, CTT_GUI);
                        timerNode = model.addComponent(timer, threadGroupNode);
                        added.add(timerNode);
                        changes.add("Added " + CTT_NAME);
                    } else if (!timerNode.isEnabled()) {
                        setEnabled(model, timerNode, true, enabledStates, properties);
                    }
                    ConstantThroughputTimer timer = (ConstantThroughputTimer) timerNode.getTestElement();
                    setProperty(properties, timer, ConstantThroughputTimer.THROUGHPUT,
                            new StringProperty(ConstantThroughputTimer.THROUGHPUT,
                                    property(prefix, "rpm", WorkloadCalculator.format(plan.rpm()))), changes);
                    setProperty(properties, timer, ConstantThroughputTimer.CALC_MODE,
                            new IntegerProperty(ConstantThroughputTimer.CALC_MODE,
                                    CALC_MODE_THREAD_GROUP_SHARED), changes);
                    model.nodeChanged(timerNode);
                }
            } else {
                JMeterTreeNode managed = managedTimer(threadGroupNode, ConstantThroughputTimer.class, CTT_NAME);
                if (managed != null && managed.isEnabled()) {
                    setEnabled(model, managed, false, enabledStates, properties);
                }
                if (hasEnabledUserThroughputTimer(threadGroupNode)) {
                    warnings.add("A user-managed Constant Throughput Timer still caps the sample rate; it was left unchanged.");
                }
            }

            JMeterTreeNode thinkNode = managedTimer(threadGroupNode, ConstantTimer.class, THINK_NAME);
            if (plan.thinkMillis() > 0) {
                if (thinkNode == null) {
                    ConstantTimer timer = new ConstantTimer();
                    timer.setName(THINK_NAME);
                    timer.setProperty(TestElement.TEST_CLASS, ConstantTimer.class.getName());
                    timer.setProperty(TestElement.GUI_CLASS, TIMER_GUI);
                    thinkNode = model.addComponent(timer, threadGroupNode);
                    added.add(thinkNode);
                    changes.add("Added " + THINK_NAME);
                } else if (!thinkNode.isEnabled()) {
                    setEnabled(model, thinkNode, true, enabledStates, properties);
                }
                ConstantTimer timer = (ConstantTimer) thinkNode.getTestElement();
                setProperty(properties, timer, ConstantTimer.DELAY,
                        new StringProperty(ConstantTimer.DELAY,
                                property(prefix, "think_ms", plan.thinkMillis())), changes);
                model.nodeChanged(thinkNode);
            } else if (thinkNode != null && thinkNode.isEnabled()) {
                setEnabled(model, thinkNode, false, enabledStates, properties);
            }
            int existingTimers = otherTimerCount(threadGroupNode);
            if (existingTimers > 0) {
                warnings.add("Your plan already has " + existingTimers
                        + " timers. The estimate does not count them.");
            }
            model.nodeStructureChanged(threadGroupNode);
            refreshCurrentGui(gui, threadGroupNode, currentGroup);
            lastApplied = new LastApplied(threadGroupNode, properties, enabledStates, added);
            return new ApplyResult(threadGroupNode.getName(), changes, warnings);
        } catch (Exception error) {
            restore(model, threadGroupNode, properties, enabledStates, added, List.of(),
                    gui, currentGroup, error);
            throw error;
        }
    }

    public static synchronized String undoLast() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null ? NOTHING_TO_UNDO : undoLast(gui.getTreeModel());
    }

    static synchronized String undoLast(JMeterTreeModel model) {
        GuiPackage gui = GuiPackage.getInstance();
        boolean currentGroup = gui != null && lastApplied != null
                && gui.getCurrentNode() == lastApplied.threadGroup;
        return undoLast(model, () -> {
            if (currentGroup) {
                gui.updateCurrentNode();
            }
        }, gui, currentGroup);
    }

    static synchronized String undoLast(JMeterTreeModel model, Runnable flushCurrentNode) {
        return undoLast(model, flushCurrentNode, null, false);
    }

    private static String undoLast(JMeterTreeModel model, Runnable flushCurrentNode,
                                   GuiPackage gui, boolean currentGroup) {
        if (lastApplied == null || model == null || lastApplied.threadGroup.getRoot() != model.getRoot()) {
            lastApplied = null;
            return NOTHING_TO_UNDO;
        }
        JMeterTreeNode group = lastApplied.threadGroup;
        flushCurrentNode.run();
        restore(model, group, lastApplied.properties, lastApplied.enabledStates,
                lastApplied.addedNodes, lastApplied.migrations, gui, currentGroup, null);
        org.qainsights.jmeter.ai.telemetry.Telemetry.record(
                org.qainsights.jmeter.ai.telemetry.TelemetryFeature.LOAD_UNDO);
        lastApplied = null;
        return "Restored the Thread Group settings and removed the timers added by the last `@load`.";
    }

    public static int countEnabledSamplers(JMeterTreeNode threadGroupNode) {
        return countSamplers(threadGroupNode, true);
    }

    public static String defaultPrefix(JMeterTreeNode threadGroupNode, int eligibleCount) {
        if (eligibleCount == 1) {
            return "load";
        }
        String name = threadGroupNode == null ? "" : threadGroupNode.getName().toLowerCase(Locale.ROOT);
        String slug = NON_SLUG.matcher(name).replaceAll("_").replaceAll("^_+|_+$", "");
        if (slug.length() > 20) {
            slug = slug.substring(0, 20).replaceAll("_+$", "");
        }
        return slug.isEmpty() ? "load" : slug;
    }

    private static void setProperty(List<PropertySnapshot> snapshots, TestElement element, String name,
                                   JMeterProperty replacement, List<String> changes) {
        setProperty(snapshots, () -> element, name, replacement, changes);
    }

    private static void setProperty(List<PropertySnapshot> snapshots, Supplier<TestElement> elementSupplier,
                                    String name, JMeterProperty replacement, List<String> changes) {
        TestElement element = elementSupplier.get();
        JMeterProperty current = element.getProperty(name);
        boolean exists = current != null && !(current instanceof NullProperty);
        snapshots.add(new PropertySnapshot(elementSupplier, name, exists ? current.clone() : null));
        element.setProperty(replacement);
        changes.add(name + " = `" + replacement.getStringValue() + "`");
    }

    private static void setEnabled(JMeterTreeModel model, JMeterTreeNode node, boolean enabled,
                                   List<EnabledSnapshot> enabledStates, List<PropertySnapshot> properties) {
        enabledStates.add(new EnabledSnapshot(node, node.isEnabled()));
        TestElement element = node.getTestElement();
        JMeterProperty current = element.getProperty(TestElement.ENABLED);
        boolean exists = current != null && !(current instanceof NullProperty);
        properties.add(new PropertySnapshot(() -> element, TestElement.ENABLED,
                exists ? current.clone() : null));
        element.setProperty(new BooleanProperty(TestElement.ENABLED, enabled));
        node.setEnabled(enabled);
        model.nodeChanged(node);
    }

    private static void restore(JMeterTreeModel model, JMeterTreeNode group, List<PropertySnapshot> properties,
                                List<EnabledSnapshot> enabledStates, List<JMeterTreeNode> added,
                                List<TreeMigration> migrations,
                                GuiPackage gui, boolean currentGroup, Exception original) {
        try {
            for (int index = added.size() - 1; index >= 0; index--) {
                JMeterTreeNode node = added.get(index);
                if (migrations.stream().anyMatch(migration -> migration.openModelNode() == node)) {
                    continue;
                }
                if (node.getParent() != null) {
                    model.removeNodeFromParent(node);
                }
            }
            for (int index = migrations.size() - 1; index >= 0; index--) {
                TreeMigration migration = migrations.get(index);
                while (migration.openModelNode().getChildCount() > 0) {
                    JMeterTreeNode child = (JMeterTreeNode) migration.openModelNode().getChildAt(0);
                    model.removeNodeFromParent(child);
                    model.insertNodeInto(child, migration.originalNode(),
                            migration.originalNode().getChildCount());
                }
            }
            for (TreeMigration migration : migrations) {
                JMeterTreeNode node = migration.openModelNode();
                if (node.getParent() != null) {
                    model.removeNodeFromParent(node);
                }
            }
            for (int index = enabledStates.size() - 1; index >= 0; index--) {
                EnabledSnapshot snapshot = enabledStates.get(index);
                snapshot.node.setEnabled(snapshot.enabled);
            }
            for (int index = properties.size() - 1; index >= 0; index--) {
                PropertySnapshot snapshot = properties.get(index);
                TestElement element = snapshot.element().get();
                if (snapshot.previous == null) {
                    element.removeProperty(snapshot.name);
                } else {
                    element.setProperty(snapshot.previous.clone());
                }
            }
            for (EnabledSnapshot snapshot : enabledStates) {
                model.nodeChanged(snapshot.node);
            }
            model.nodeStructureChanged(group);
            for (TreeMigration migration : migrations) {
                model.nodeStructureChanged(migration.originalNode());
            }
            refreshCurrentGui(gui, group, currentGroup);
        } catch (Exception restoreError) {
            if (original != null) {
                original.addSuppressed(restoreError);
            } else {
                log.warn("Could not completely restore the Thread Group after @load", restoreError);
            }
        }
    }

    private static String property(String prefix, String suffix, Object value) {
        return "${__P(" + prefix + "." + suffix + "," + value + ")}";
    }

    private static JMeterTreeNode enabledDirectChild(JMeterTreeNode parent, Class<?> type) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (type.isInstance(child.getTestElement()) && child.isEnabled()) {
                return child;
            }
        }
        return null;
    }

    private static JMeterTreeNode managedTimer(JMeterTreeNode parent, Class<?> type, String name) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (type.isInstance(child.getTestElement()) && name.equals(child.getTestElement().getName())) {
                return child;
            }
        }
        return null;
    }

    private static boolean hasEnabledUserThroughputTimer(JMeterTreeNode parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (child.getTestElement() instanceof ConstantThroughputTimer
                    && child.isEnabled() && !CTT_NAME.equals(child.getTestElement().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUserOwnedThroughputTimerInScope(JMeterTreeNode node, boolean ancestorsEnabled) {
        if (!ancestorsEnabled || !node.isEnabled()) {
            return false;
        }
        if (node.getTestElement() instanceof ConstantThroughputTimer
                && !CTT_NAME.equals(node.getTestElement().getName())) {
            return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            if (hasUserOwnedThroughputTimerInScope((JMeterTreeNode) node.getChildAt(index), true)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnabledShapingTimer(JMeterTreeNode node, boolean ancestorsEnabled) {
        if (!ancestorsEnabled || !node.isEnabled()) {
            return false;
        }
        String className = node.getTestElement().getClass().getName();
        if (className.endsWith(".PreciseThroughputTimer") || className.contains("ThroughputShapingTimer")) {
            return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            if (hasEnabledShapingTimer((JMeterTreeNode) node.getChildAt(index), true)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnabledNestedThroughputTimer(JMeterTreeNode threadGroupNode) {
        for (int index = 0; index < threadGroupNode.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) threadGroupNode.getChildAt(index);
            if (!child.isEnabled()) {
                continue;
            }
            if (!(child.getTestElement() instanceof ConstantThroughputTimer)
                    && hasEnabledThroughputTimer(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnabledThroughputTimer(JMeterTreeNode node) {
        for (int index = 0; index < node.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(index);
            if (!child.isEnabled()) {
                continue;
            }
            if (child.getTestElement() instanceof ConstantThroughputTimer || hasEnabledThroughputTimer(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsShapingTimer(JMeterTreeNode node) {
        if (node.getTestElement() != null) {
            String className = node.getTestElement().getClass().getName();
            if (className.endsWith(".PreciseThroughputTimer") || className.contains("ThroughputShapingTimer")) {
                return true;
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            if (containsShapingTimer((JMeterTreeNode) node.getChildAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static int otherTimerCount(JMeterTreeNode node) {
        int count = 0;
        for (int index = 0; index < node.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(index);
            if (child.isEnabled()) {
                Object element = child.getTestElement();
                if (element instanceof Timer && !(element instanceof ConstantThroughputTimer)
                        && !(element instanceof ConstantTimer && THINK_NAME.equals(((TestElement) element).getName()))) {
                    count++;
                }
                count += otherTimerCount(child);
            }
        }
        return count;
    }

    private static int countSamplers(JMeterTreeNode node, boolean ancestorEnabled) {
        if (!ancestorEnabled || !node.isEnabled()) {
            return 0;
        }
        int count = node.getTestElement() instanceof Sampler ? 1 : 0;
        for (int index = 0; index < node.getChildCount(); index++) {
            count += countSamplers((JMeterTreeNode) node.getChildAt(index), true);
        }
        return count;
    }

    private static void refreshCurrentGui(GuiPackage gui, JMeterTreeNode node, boolean currentGroup) {
        if (gui == null || !currentGroup) {
            return;
        }
        try {
            JMeterGUIComponent component = gui.getGui(node.getTestElement());
            if (component != null) {
                component.clearGui();
                component.configure(node.getTestElement());
            }
            if (gui.getMainFrame() != null) {
                gui.getMainFrame().repaint();
            }
        } catch (RuntimeException e) {
            log.debug("Could not refresh the Thread Group form after @load", e);
        }
    }

    public record ApplyResult(String threadGroupName, List<String> changes, List<String> warnings) {
        public ApplyResult {
            changes = List.copyOf(changes);
            warnings = List.copyOf(warnings);
        }
    }

    private record PropertySnapshot(Supplier<TestElement> element, String name, JMeterProperty previous) {
    }

    private record EnabledSnapshot(JMeterTreeNode node, boolean enabled) {
    }

    private record TreeMigration(JMeterTreeNode originalNode, JMeterTreeNode openModelNode) {
    }

    private record LastApplied(JMeterTreeNode threadGroup, List<PropertySnapshot> properties,
                               List<EnabledSnapshot> enabledStates, List<JMeterTreeNode> addedNodes,
                               List<TreeMigration> migrations) {
        private LastApplied(JMeterTreeNode threadGroup, List<PropertySnapshot> properties,
                            List<EnabledSnapshot> enabledStates, List<JMeterTreeNode> addedNodes) {
            this(threadGroup, properties, enabledStates, addedNodes, List.of());
        }
    }
}
