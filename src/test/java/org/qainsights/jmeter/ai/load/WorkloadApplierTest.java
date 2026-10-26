package org.qainsights.jmeter.ai.load;

import java.util.List;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.timers.ConstantThroughputTimer;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.timers.poissonarrivals.PreciseThroughputTimer;
import org.apache.jmeter.threads.PostThreadGroup;
import org.apache.jmeter.threads.SetupThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkloadApplierTest {

    @Test
    void appliesThreadGroupExpressionsAndAddsASharedRateTimer() throws Exception {
        Fixture fixture = fixture("Checkout", true);
        WorkloadPlan plan = plan("shop", 150, 600, 4200, 12_000.0, 0);

        WorkloadApplier.ApplyResult result = WorkloadApplier.apply(fixture.model, fixture.groupNode, plan);

        assertEquals("${__P(shop.threads,150)}",
                fixture.group.getPropertyAsString(ThreadGroup.NUM_THREADS));
        assertEquals("${__P(shop.rampup,600)}",
                fixture.group.getPropertyAsString(ThreadGroup.RAMP_TIME));
        assertEquals("true", fixture.group.getPropertyAsString(ThreadGroup.SCHEDULER));
        assertEquals("${__P(shop.duration,4200)}",
                fixture.group.getPropertyAsString(ThreadGroup.DURATION));
        assertEquals(-1, fixture.loop.getLoops());
        ConstantThroughputTimer timer = directChild(fixture.groupNode, ConstantThroughputTimer.class);
        assertEquals("${__P(shop.rpm,12000)}", timer.getPropertyAsString(ConstantThroughputTimer.THROUGHPUT));
        int appliedMode = timer.getCalcMode();
        ConstantThroughputTimer validationTimer = new ConstantThroughputTimer();
        validationTimer.setCalcMode(appliedMode);
        assertEquals(4, validationTimer.getCalcMode());
        assertEquals(8, result.changes().size());
        assertTrue(result.changes().contains("Added " + WorkloadApplier.CTT_NAME));
    }

    @Test
    void usesTheMainLoopControllerAndUndoRestoresItsLoops() throws Exception {
        Fixture fixture = fixture("Main loop", true);
        fixture.loop.setLoops(8);
        LoopController nested = new LoopController();
        nested.setLoops(3);
        fixture.model.addComponent(nested, fixture.groupNode);

        WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 40, 60, 660, 2400.0, 0));

        assertEquals(-1, fixture.loop.getLoops());
        assertEquals(3, nested.getLoops());
        WorkloadApplier.undoLast(fixture.model);
        assertEquals(8, fixture.loop.getLoops());
        assertEquals(3, nested.getLoops());
    }

    @Test
    void rollsBackAllChangesWhenTreeMutationFails() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel() {
            @Override
            public void nodeChanged(javax.swing.tree.TreeNode node) {
                if (node instanceof JMeterTreeNode jmeterNode
                        && jmeterNode.getTestElement() instanceof ConstantThroughputTimer) {
                    throw new IllegalStateException("forced tree failure");
                }
                super.nodeChanged(node);
            }
        };
        ThreadGroup group = new ThreadGroup();
        group.setName("Rollback");
        LoopController loop = new LoopController();
        group.setSamplerController(loop);
        loop.setLoops(7);
        JMeterTreeNode groupNode = model.addComponent(group, planNode(model));

        assertThrows(IllegalStateException.class, () -> WorkloadApplier.apply(model, groupNode,
                plan("load", 40, 60, 660, 2400.0, 0)));
        assertTrue(group.getProperty(ThreadGroup.NUM_THREADS)
                instanceof org.apache.jmeter.testelement.property.NullProperty);
        assertTrue(group.getProperty(ThreadGroup.RAMP_TIME)
                instanceof org.apache.jmeter.testelement.property.NullProperty);
        assertTrue(group.getProperty(ThreadGroup.DURATION)
                instanceof org.apache.jmeter.testelement.property.NullProperty);
        assertEquals(7, loop.getLoops());
        assertEquals(0, countChildren(groupNode, ConstantThroughputTimer.class));
    }

    @Test
    void updatesAnExistingConstantThroughputTimerWithoutDuplicatingIt() throws Exception {
        Fixture fixture = fixture("Existing", true);
        ConstantThroughputTimer existing = new ConstantThroughputTimer();
        existing.setName("Existing target");
        existing.setProperty(new StringProperty(ConstantThroughputTimer.THROUGHPUT, "250"));
        JMeterTreeNode timerNode = fixture.model.addComponent(existing, fixture.groupNode);

        WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, 600.0, 0));

        assertEquals(1, countChildren(fixture.groupNode, ConstantThroughputTimer.class));
        assertEquals("${__P(load.rpm,600)}", existing.getPropertyAsString(ConstantThroughputTimer.THROUGHPUT));
        assertTrue(timerNode.getParent() == fixture.groupNode);
    }

    @Test
    void reEnablesOnlyTheManagedDisabledThroughputTimerAndUndoRestoresItsEnabledFlag() throws Exception {
        Fixture fixture = fixture("Managed timer", true);
        ConstantThroughputTimer managed = new ConstantThroughputTimer();
        managed.setName(WorkloadApplier.CTT_NAME);
        JMeterTreeNode managedNode = fixture.model.addComponent(managed, fixture.groupNode);
        managedNode.setEnabled(false);

        WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, 600.0, 0));

        assertTrue(managedNode.isEnabled());
        assertEquals("${__P(load.rpm,600)}", managed.getPropertyAsString(ConstantThroughputTimer.THROUGHPUT));
        WorkloadApplier.undoLast(fixture.model);
        assertFalse(managedNode.isEnabled());
    }

    @Test
    void disablesManagedTimersWhenRateAndThinkTimeAreClearedAndLeavesUserTimerAlone() throws Exception {
        Fixture fixture = fixture("Cleared timers", true);
        ConstantThroughputTimer managedRate = new ConstantThroughputTimer();
        managedRate.setName(WorkloadApplier.CTT_NAME);
        JMeterTreeNode managedRateNode = fixture.model.addComponent(managedRate, fixture.groupNode);
        ConstantThroughputTimer userRate = new ConstantThroughputTimer();
        userRate.setName("User rate");
        JMeterTreeNode userRateNode = fixture.model.addComponent(userRate, fixture.groupNode);
        ConstantTimer managedThink = new ConstantTimer();
        managedThink.setName(WorkloadApplier.THINK_NAME);
        JMeterTreeNode managedThinkNode = fixture.model.addComponent(managedThink, fixture.groupNode);

        WorkloadApplier.ApplyResult result = WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, null, 0));

        assertFalse(managedRateNode.isEnabled());
        assertFalse(managedThinkNode.isEnabled());
        assertTrue(userRateNode.isEnabled());
        assertTrue(result.warnings().stream().anyMatch(text -> text.contains("still caps the sample rate")));
        WorkloadApplier.undoLast(fixture.model);
        assertTrue(managedRateNode.isEnabled());
        assertTrue(managedThinkNode.isEnabled());
        assertTrue(userRateNode.isEnabled());
    }

    @Test
    void shapingTimerPreventsThroughputTimerMutationAndAddsWarning() throws Exception {
        Fixture fixture = fixture("Shaped", true);
        PreciseThroughputTimer precise = new PreciseThroughputTimer();
        fixture.model.addComponent(precise, fixture.groupNode);

        WorkloadApplier.ApplyResult result = WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, 600.0, 0));

        assertEquals(0, countChildren(fixture.groupNode, ConstantThroughputTimer.class));
        assertTrue(result.warnings().stream().anyMatch(text -> text.contains("no Constant Throughput Timer")));
    }

    @Test
    void nestedThroughputTimerPreventsAddingOrChangingAGroupTimer() throws Exception {
        Fixture fixture = fixture("Nested rate", true);
        LoopController nestedController = new LoopController();
        JMeterTreeNode controllerNode = fixture.model.addComponent(nestedController, fixture.groupNode);
        ConstantThroughputTimer nestedTimer = new ConstantThroughputTimer();
        nestedTimer.setName("Nested user rate");
        nestedTimer.setProperty(new StringProperty(ConstantThroughputTimer.THROUGHPUT, "300"));
        JMeterTreeNode nestedTimerNode = fixture.model.addComponent(nestedTimer, controllerNode);

        WorkloadApplier.ApplyResult result = WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, 600.0, 0));

        assertNull(directChild(fixture.groupNode, ConstantThroughputTimer.class));
        assertEquals("300", nestedTimer.getPropertyAsString(ConstantThroughputTimer.THROUGHPUT));
        assertTrue(nestedTimerNode.isEnabled());
        assertTrue(result.warnings().stream().anyMatch(text -> text.contains("nested under a controller")));
    }

    @Test
    void addsOrUpdatesTheNamedThinkTimerAndReportsOtherTimers() throws Exception {
        Fixture fixture = fixture("Thinking", true);
        ConstantTimer other = new ConstantTimer();
        other.setName("Existing think timer");
        fixture.model.addComponent(other, fixture.groupNode);

        WorkloadApplier.ApplyResult result = WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, 600.0, 3000));

        ConstantTimer think = directChild(fixture.groupNode, ConstantTimer.class, WorkloadApplier.THINK_NAME);
        assertEquals("${__P(load.think_ms,3000)}", think.getDelay());
        assertTrue(result.warnings().contains("Your plan already has 1 timers. The estimate does not count them."));

        WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, 600.0, 1500));
        assertEquals(1, countChildren(fixture.groupNode, ConstantTimer.class, WorkloadApplier.THINK_NAME));
        assertEquals("${__P(load.think_ms,1500)}", think.getDelay());
    }

    @Test
    void undoRestoresExactPropertyTypesAndValuesAndRemovesAddedNodes() throws Exception {
        Fixture fixture = fixture("Undo", true);
        fixture.group.setProperty(new IntegerProperty(ThreadGroup.NUM_THREADS, 7));
        fixture.group.setProperty(new StringProperty(ThreadGroup.RAMP_TIME, "12"));
        fixture.group.setProperty(new StringProperty(ThreadGroup.SCHEDULER, "false"));
        fixture.group.setProperty(new IntegerProperty(ThreadGroup.DURATION, 9));
        fixture.loop.setProperty(new StringProperty(LoopController.LOOPS, "3"));

        WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 40, 60, 660, 2400.0, 2000));
        assertEquals(1, countChildren(fixture.groupNode, ConstantTimer.class));

        assertTrue(WorkloadApplier.undoLast(fixture.model).contains("Restored the Thread Group"));
        assertInstanceOf(IntegerProperty.class, fixture.group.getProperty(ThreadGroup.NUM_THREADS));
        assertEquals("7", fixture.group.getPropertyAsString(ThreadGroup.NUM_THREADS));
        assertInstanceOf(StringProperty.class, fixture.group.getProperty(ThreadGroup.RAMP_TIME));
        assertEquals("12", fixture.group.getPropertyAsString(ThreadGroup.RAMP_TIME));
        assertEquals("false", fixture.group.getPropertyAsString(ThreadGroup.SCHEDULER));
        assertEquals("9", fixture.group.getPropertyAsString(ThreadGroup.DURATION));
        assertEquals("3", fixture.loop.getPropertyAsString(LoopController.LOOPS));
        assertTrue(fixture.group.getProperty("unknown.load.property")
                instanceof org.apache.jmeter.testelement.property.NullProperty);
        assertEquals(0, countChildren(fixture.groupNode, ConstantThroughputTimer.class));
        assertEquals(0, countChildren(fixture.groupNode, ConstantTimer.class));
        assertEquals(WorkloadApplier.NOTHING_TO_UNDO, WorkloadApplier.undoLast(fixture.model));
    }

    @Test
    void undoDoesNothingWhenTheThreadGroupIsNoLongerInThePlan() throws Exception {
        Fixture fixture = fixture("Detached", true);
        WorkloadApplier.apply(fixture.model, fixture.groupNode,
                plan("load", 20, 60, 660, 1200.0, 0));
        fixture.model.removeNodeFromParent(fixture.groupNode);

        assertEquals(WorkloadApplier.NOTHING_TO_UNDO, WorkloadApplier.undoLast(fixture.model));
    }

    @Test
    void generatesSingleAndSluggedPrefixes() throws Exception {
        Fixture single = fixture("Any Name", true);
        assertEquals("load", LoadCommandHandler.prefixFor(single.groupNode, 1));
        assertEquals("any_name", LoadCommandHandler.prefixFor(single.groupNode, 2));

        ThreadGroup longNamed = new ThreadGroup();
        longNamed.setName("My Very Long Checkout Group");
        JMeterTreeNode node = new JMeterTreeNode(longNamed, single.model);
        assertEquals("my_very_long_checkou", LoadCommandHandler.prefixFor(node, 2));
        assertEquals("load", LoadCommandHandler.prefixFor(null, 2));
    }

    @Test
    void countsEnabledSamplersRecursivelyAndSkipsDisabledSubtrees() throws Exception {
        Fixture fixture = fixture("Census", true);
        JMeterTreeNode disabled = fixture.model.addComponent(new HTTPSamplerProxy(), fixture.groupNode);
        disabled.setEnabled(false);
        fixture.model.addComponent(new HTTPSamplerProxy(), fixture.groupNode);

        assertEquals(2, WorkloadApplier.countEnabledSamplers(fixture.groupNode));
    }

    @Test
    void eligibleGroupsExcludeDisabledAndSpecialThreadGroupsAndDefaultToSelectedAncestor() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode planNode = planNode(model);
        ThreadGroup first = new ThreadGroup();
        first.setName("First");
        first.setSamplerController(new LoopController());
        JMeterTreeNode firstNode = model.addComponent(first, planNode);
        model.addComponent(new HTTPSamplerProxy(), firstNode);
        ThreadGroup second = new ThreadGroup();
        second.setName("Second");
        second.setSamplerController(new LoopController());
        JMeterTreeNode secondNode = model.addComponent(second, planNode);
        model.addComponent(new HTTPSamplerProxy(), secondNode);
        ThreadGroup disabledGroup = new ThreadGroup();
        JMeterTreeNode disabledNode = model.addComponent(disabledGroup, planNode);
        disabledNode.setEnabled(false);
        model.addComponent(new SetupThreadGroup(), planNode);
        model.addComponent(new PostThreadGroup(), planNode);

        assertEquals(List.of(firstNode, secondNode), LoadCommandHandler.eligibleThreadGroups(model));
        LoadCommandHandler.Preparation preparation = LoadCommandHandler.prepare("@load 100 rps",
                (JMeterTreeNode) secondNode.getChildAt(0), model);
        assertNull(preparation.error());
        assertEquals(secondNode, preparation.selectedGroup());
        assertEquals(2, preparation.eligibleCount());
    }

    @Test
    void reportsMissingGroupsAndGroupsWithoutRequests() {
        assertEquals(LoadCommandHandler.NO_THREAD_GROUP,
                LoadCommandHandler.prepare("@load 100 rps", null, new JMeterTreeModel()).error());
        JMeterTreeModel model = new JMeterTreeModel();
        ThreadGroup group = new ThreadGroup();
        JMeterTreeNode node = addGroup(model, group);
        assertEquals(LoadCommandHandler.NO_REQUESTS,
                LoadCommandHandler.prepare("@load 100 rps", node, model).error());
    }

    private static Fixture fixture(String name, boolean withSampler) throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        ThreadGroup group = new ThreadGroup();
        group.setName(name);
        LoopController loop = new LoopController();
        group.setSamplerController(loop);
        JMeterTreeNode groupNode = model.addComponent(group, planNode(model));
        if (withSampler) {
            model.addComponent(new HTTPSamplerProxy(), groupNode);
        }
        return new Fixture(model, group, groupNode, loop);
    }

    private static JMeterTreeNode addGroup(JMeterTreeModel model, ThreadGroup group) {
        try {
            return model.addComponent(group, planNode(model));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JMeterTreeNode planNode(JMeterTreeModel model) {
        return (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
    }

    private static WorkloadPlan plan(String prefix, int threads, long ramp, long duration,
                                     Double rpm, long think) {
        return new WorkloadPlan(WorkloadInputs.TargetType.REQUEST_RATE, 10, threads, 1,
                rpm == null ? null : rpm / 60.0, null, rpm, ramp, duration - ramp, duration,
                1, null, null, 500, think, prefix, List.of(), List.of(), "");
    }

    private static <T> T directChild(JMeterTreeNode parent, Class<T> type) {
        return directChild(parent, type, null);
    }

    private static <T> T directChild(JMeterTreeNode parent, Class<T> type, String name) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (type.isInstance(child.getTestElement())
                    && (name == null || name.equals(child.getName()))) {
                return type.cast(child.getTestElement());
            }
        }
        return null;
    }

    private static int countChildren(JMeterTreeNode parent, Class<?> type) {
        return countChildren(parent, type, null);
    }

    private static int countChildren(JMeterTreeNode parent, Class<?> type, String name) {
        int count = 0;
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (type.isInstance(child.getTestElement())
                    && (name == null || name.equals(child.getName()))) {
                count++;
            }
        }
        return count;
    }

    private record Fixture(JMeterTreeModel model, ThreadGroup group, JMeterTreeNode groupNode,
                           LoopController loop) {
    }
}
