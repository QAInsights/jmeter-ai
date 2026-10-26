package org.qainsights.jmeter.ai.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies a calculated workload and retains the last edit for Ctrl+Z undo. */
public final class WorkloadApplier {
    static final String NOTHING_TO_UNDO = "Nothing to undo: the last `@load` changes are no longer in the plan.";
    static final String CTT_NAME = "Target throughput (Feather Wand)";
    static final String THINK_NAME = "Think time (Feather Wand)";
    static final String CTT_GUI = "org.apache.jmeter.testbeans.gui.TestBeanGUI";
    static final String TIMER_GUI = "org.apache.jmeter.timers.gui.ConstantTimerGui";
    // The zero-based mode index for all active threads in this Thread Group (shared).
    static final int CALC_MODE_THREAD_GROUP_SHARED = 4;
    private static final Logger log = LoggerFactory.getLogger(WorkloadApplier.class);
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static LastApplied lastApplied;

    private WorkloadApplier() {
    }

    public static synchronized ApplyResult apply(JMeterTreeModel model, JMeterTreeNode threadGroupNode,
                                                WorkloadPlan plan) throws IllegalUserActionException {
        if (model == null || threadGroupNode == null || threadGroupNode.getRoot() != model.getRoot()
                || !(threadGroupNode.getTestElement() instanceof ThreadGroup)) {
            throw new IllegalArgumentException("The selected Thread Group is no longer in the test plan.");
        }
        List<PropertySnapshot> properties = new ArrayList<>();
        List<JMeterTreeNode> added = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ThreadGroup group = (ThreadGroup) threadGroupNode.getTestElement();
        String prefix = plan.propertyPrefix();
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

        JMeterTreeNode loopNode = findLoopController(threadGroupNode);
        if (loopNode == null) {
            throw new IllegalStateException("The Thread Group has no Loop Controller.");
        }
        setProperty(properties, loopNode.getTestElement(), LoopController.LOOPS,
                new IntegerProperty(LoopController.LOOPS, LoopController.INFINITE_LOOP_COUNT), changes);
        loopNode.setName(loopNode.getTestElement().getName());
        model.nodeChanged(loopNode);

        if (plan.rpm() != null) {
            if (containsShapingTimer(threadGroupNode)) {
                warnings.add("A Precise Throughput Timer or Throughput Shaping Timer already controls rate; "
                        + "no Constant Throughput Timer was changed.");
            } else {
                ConstantThroughputTimer timer = directChild(threadGroupNode, ConstantThroughputTimer.class);
                if (timer == null) {
                    timer = new ConstantThroughputTimer();
                    timer.setName(CTT_NAME);
                    timer.setProperty(TestElement.TEST_CLASS, ConstantThroughputTimer.class.getName());
                    timer.setProperty(TestElement.GUI_CLASS, CTT_GUI);
                    JMeterTreeNode timerNode = model.addComponent(timer, threadGroupNode);
                    added.add(timerNode);
                    changes.add("Added " + CTT_NAME);
                }
                setProperty(properties, timer, ConstantThroughputTimer.THROUGHPUT,
                new StringProperty(ConstantThroughputTimer.THROUGHPUT,
                                property(prefix, "rpm", WorkloadCalculator.format(plan.rpm()))), changes);
                setProperty(properties, timer, ConstantThroughputTimer.CALC_MODE,
                        new IntegerProperty(ConstantThroughputTimer.CALC_MODE, CALC_MODE_THREAD_GROUP_SHARED), changes);
                JMeterTreeNode timerNode = model.getNodeOf(timer);
                if (timerNode != null) {
                    model.nodeChanged(timerNode);
                }
            }
        }

        if (plan.thinkMillis() > 0) {
            ConstantTimer timer = directThinkTimer(threadGroupNode);
            if (timer == null) {
                timer = new ConstantTimer();
                timer.setName(THINK_NAME);
                timer.setProperty(TestElement.TEST_CLASS, ConstantTimer.class.getName());
                timer.setProperty(TestElement.GUI_CLASS, TIMER_GUI);
                JMeterTreeNode timerNode = model.addComponent(timer, threadGroupNode);
                added.add(timerNode);
                changes.add("Added " + THINK_NAME);
            }
            setProperty(properties, timer, ConstantTimer.DELAY,
                    new StringProperty(ConstantTimer.DELAY, property(prefix, "think_ms", plan.thinkMillis())),
                    changes);
            JMeterTreeNode timerNode = model.getNodeOf(timer);
            if (timerNode != null) {
                model.nodeChanged(timerNode);
            }
        }
        int existingTimers = otherTimerCount(threadGroupNode);
        if (existingTimers > 0) {
            warnings.add("Your plan already has " + existingTimers
                    + " timers. The estimate does not count them.");
        }
        model.nodeStructureChanged(threadGroupNode);
        refreshCurrentGui(threadGroupNode);
        lastApplied = new LastApplied(threadGroupNode, properties, added);
        return new ApplyResult(threadGroupNode.getName(), changes, warnings);
    }

    public static synchronized String undoLast() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null ? NOTHING_TO_UNDO : undoLast(gui.getTreeModel());
    }

    static synchronized String undoLast(JMeterTreeModel model) {
        if (lastApplied == null || model == null || lastApplied.threadGroup.getRoot() != model.getRoot()) {
            lastApplied = null;
            return NOTHING_TO_UNDO;
        }
        for (int index = lastApplied.addedNodes.size() - 1; index >= 0; index--) {
            JMeterTreeNode node = lastApplied.addedNodes.get(index);
            if (node.getParent() != null) {
                model.removeNodeFromParent(node);
            }
        }
        for (int index = lastApplied.properties.size() - 1; index >= 0; index--) {
            PropertySnapshot snapshot = lastApplied.properties.get(index);
            if (snapshot.previous == null) {
                snapshot.element.removeProperty(snapshot.name);
            } else {
                snapshot.element.setProperty(snapshot.previous.clone());
            }
        }
        org.qainsights.jmeter.ai.telemetry.Telemetry.record(
                org.qainsights.jmeter.ai.telemetry.TelemetryFeature.LOAD_UNDO);
        JMeterTreeNode group = lastApplied.threadGroup;
        model.nodeStructureChanged(group);
        refreshCurrentGui(group);
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
        JMeterProperty current = element.getProperty(name);
        boolean exists = current != null && !(current instanceof NullProperty);
        snapshots.add(new PropertySnapshot(element, name, exists ? current.clone() : null));
        element.setProperty(replacement);
        changes.add(name + " = `" + replacement.getStringValue() + "`");
    }

    private static String property(String prefix, String suffix, Object value) {
        return "${__P(" + prefix + "." + suffix + "," + value + ")}";
    }

    private static JMeterTreeNode findLoopController(JMeterTreeNode parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (child.getTestElement() instanceof LoopController) {
                return child;
            }
            JMeterTreeNode nested = findLoopController(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static <T> T directChild(JMeterTreeNode parent, Class<T> type) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            Object element = ((JMeterTreeNode) parent.getChildAt(index)).getTestElement();
            if (type.isInstance(element)) {
                return type.cast(element);
            }
        }
        return null;
    }

    private static ConstantTimer directThinkTimer(JMeterTreeNode parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (child.getTestElement() instanceof ConstantTimer
                    && THINK_NAME.equals(child.getTestElement().getName())) {
                return (ConstantTimer) child.getTestElement();
            }
        }
        return null;
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

    private static void refreshCurrentGui(JMeterTreeNode node) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getCurrentNode() != node || gui.getCurrentGui() == null) {
            return;
        }
        try {
            gui.getCurrentGui().configure(node.getTestElement());
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

    private record PropertySnapshot(TestElement element, String name, JMeterProperty previous) {
    }

    private record LastApplied(JMeterTreeNode threadGroup, List<PropertySnapshot> properties,
                               List<JMeterTreeNode> addedNodes) {
    }
}
