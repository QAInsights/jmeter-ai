package org.qainsights.jmeter.ai.load;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
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
        JMeterTreeNode firstNode = model.addComponent(first, plan);
        model.addComponent(new HTTPSamplerProxy(), firstNode);
        ThreadGroup next = new ThreadGroup();
        JMeterTreeNode nextNode = model.addComponent(next, plan);
        model.addComponent(new HTTPSamplerProxy(), nextNode);

        LoadCommandHandler.Preparation preparation = LoadCommandHandler.prepare("@load 20 rps", null, model);

        assertNull(preparation.error());
        assertEquals(firstNode, preparation.selectedGroup());
        assertEquals("plan.jmx", preparation.planFileName());
        assertNotNull(preparation.spec().requestsPerSecond());
    }
}
