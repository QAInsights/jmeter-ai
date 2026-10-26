package org.qainsights.jmeter.ai.load;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.jdbc.config.DataSourceElement;
import org.apache.jmeter.protocol.jdbc.sampler.FakeJDBCSampler;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
