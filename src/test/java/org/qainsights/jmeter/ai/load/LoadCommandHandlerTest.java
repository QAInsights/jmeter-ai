package org.qainsights.jmeter.ai.load;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.jdbc.config.DataSourceElement;
import org.apache.jmeter.protocol.jdbc.sampler.FakeJDBCSampler;
import org.apache.jmeter.modifiers.JSR223PreProcessor;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadCommandHandlerTest {

    @Test
    void reportsUnavailableGuiAndSelectsFirstEligibleGroupWhenNothingIsSelected() throws Exception {
        assertEquals(LoadCommandHandler.NO_GUI, LoadCommandHandler.prepare("@load 20 rps", null, null).error());
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        ThreadGroup first = new ThreadGroup();
        first.setName("First");
        first.setSamplerController(new LoopController());
        JMeterTreeNode firstNode = model.addComponent(first, plan);
        ThreadGroup next = new ThreadGroup();
        next.setSamplerController(new LoopController());
        JMeterTreeNode nextNode = model.addComponent(next, plan);
        model.addComponent(new HTTPSamplerProxy(), nextNode);
        ThreadGroup later = new ThreadGroup();
        later.setSamplerController(new LoopController());
        JMeterTreeNode laterNode = model.addComponent(later, plan);
        model.addComponent(new HTTPSamplerProxy(), laterNode);

        LoadCommandHandler.Preparation preparation = LoadCommandHandler.prepare("@load 20 rps", null, model);

        assertNull(preparation.error());
        assertEquals(nextNode, preparation.selectedGroup());
        assertEquals("plan.jmx", preparation.planFileName());
        assertNotNull(preparation.spec().requestsPerSecond());
    }

    @Test
    void noArgumentLoadPreparesAnEmptyTargetForTheFirstEligibleGroup() throws Exception {
        JMeterTreeModel model = modelWithSampler();

        LoadCommandHandler.Preparation preparation =
                LoadCommandHandler.prepare("@load", null, model, key -> "");

        assertNull(preparation.error());
        assertTrue(preparation.spec().isEmpty());
        assertNotNull(preparation.selectedGroup());
    }

    @Test
    void redirectsFromConvertedOriginalAndProvidesInfoNote() throws Exception {
        JMeterTreeModel model = modelWithSampler();
        JMeterTreeNode original = LoadCommandHandler.eligibleThreadGroups(model).get(0);
        ((ThreadGroup) original.getTestElement()).setName("Checkout");
        original.setEnabled(false);
        OpenModelThreadGroup converted = new OpenModelThreadGroup();
        converted.setName("Checkout (Step)");
        converted.setProperty(WorkloadApplier.OPEN_MODEL_SCHEDULE,
                "rate(${__P(load.s1.rate,1)}/sec)");
        JMeterTreeNode convertedNode = model.addComponent(converted, (JMeterTreeNode) original.getParent());
        model.addComponent(new HTTPSamplerProxy(), convertedNode);

        LoadCommandHandler.Preparation preparation = LoadCommandHandler.prepare(
                "@load step 100 to 500 rps by 100 every 5 min", original, model, key -> "");

        assertNull(preparation.error());
        assertEquals(convertedNode, preparation.selectedGroup());
        assertEquals("\"Checkout\" was converted to \"Checkout (Step)\". "
                + "Editing \"Checkout (Step)\".", preparation.redirectNote());
    }

    @Test
    void collectsEnabledJdbcPoolSettingsAndSkipsDisabledSubtrees() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        JMeterTreeNode groupNode = model.addComponent(group,
                (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        DataSourceElement enabledPool = new DataSourceElement();
        enabledPool.setProperty("dataSource", "payments");
        enabledPool.setProperty("poolMax", 10);
        model.addComponent(enabledPool, groupNode);
        DataSourceElement disabledPool = new DataSourceElement();
        disabledPool.setProperty("dataSource", "disabled");
        disabledPool.setProperty("poolMax", 20);
        JMeterTreeNode disabledNode = model.addComponent(disabledPool, groupNode);
        disabledNode.setEnabled(false);

        assertEquals(java.util.List.of(new JdbcPool("payments", 10)),
                LoadCommandHandler.jdbcPools(groupNode));
    }

    @Test
    void collectsEnabledSamplerClassesAndSkipsDisabledSubtrees() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        JMeterTreeNode groupNode = model.addComponent(group,
                (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        model.addComponent(new FakeJDBCSampler(), groupNode);
        JMeterTreeNode disabledNode = model.addComponent(new HTTPSamplerProxy(), groupNode);
        disabledNode.setEnabled(false);

        assertEquals(java.util.List.of(FakeJDBCSampler.class),
                LoadCommandHandler.enabledSamplerClasses(groupNode));
    }

    @Test
    void prepopulatesValidatedRemoteHostsFromInjectedPropertyLookup() throws Exception {
        JMeterTreeModel model = modelWithSampler();
        LoadCommandHandler.Preparation preparation = LoadCommandHandler.prepare(
                "@load 200 rps across 4 agents", null, model,
                key -> key.equals("remote_hosts") ? " load-a,load-b,load-c,load-d " : "");

        assertEquals(java.util.List.of("load-a", "load-b", "load-c", "load-d"),
                preparation.generatorHosts());
        assertEquals(true, preparation.hostsFromProperties());
    }

    @Test
    void ignoresLocalhostOnlyRemoteHostPropertiesIncludingPorts() {
        assertEquals(java.util.List.of(), LoadCommandHandler.remoteHosts("localhost:1099, 127.0.0.1", null));
        assertEquals(java.util.List.of(), LoadCommandHandler.remoteHosts("127.0.0.1:1099, localhost:2", null));
    }

    @Test
    void ignoresInvalidOrAgentCountMismatchedRemoteHosts() {
        assertEquals(java.util.List.of(), LoadCommandHandler.remoteHosts("load-a, bad host", null));
        assertEquals(java.util.List.of(), LoadCommandHandler.remoteHosts("load-a,load-b", 3));
    }

    @Test
    void detectsEnabledJsr223PreProcessorsAsScriptHeavy() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        JMeterTreeNode groupNode = model.addComponent(group,
                (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        JMeterTreeNode scriptNode = model.addComponent(new JSR223PreProcessor(), groupNode);

        assertEquals(true, LoadCommandHandler.scriptHeavy(groupNode));
        scriptNode.setEnabled(false);
        assertEquals(false, LoadCommandHandler.scriptHeavy(groupNode));
    }

    private static JMeterTreeModel modelWithSampler() throws Exception {
        JMeterTreeModel model = new JMeterTreeModel();
        ThreadGroup group = new ThreadGroup();
        group.setSamplerController(new LoopController());
        JMeterTreeNode groupNode = model.addComponent(group,
                (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        model.addComponent(new HTTPSamplerProxy(), groupNode);
        return model;
    }
}
