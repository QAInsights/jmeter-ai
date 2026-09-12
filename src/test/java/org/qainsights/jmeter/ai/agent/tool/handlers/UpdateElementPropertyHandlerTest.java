package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.PropertyUpdater;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link UpdateElementPropertyHandler} using an in-memory tree and fake updater. */
class UpdateElementPropertyHandlerTest {

    /** Records the last update and toggles success. */
    private static final class FakeUpdater implements PropertyUpdater {
        JMeterTreeNode lastNode;
        String lastProperty;
        String lastValue;
        boolean succeed = true;

        @Override
        public boolean update(JMeterTreeNode node, String property, String value) {
            this.lastNode = node;
            this.lastProperty = property;
            this.lastValue = value;
            return succeed;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode root;
    private JMeterTreeNode httpRequest;
    private JMeterTreeNode realThreadGroup;
    private FakeUpdater updater;
    private Tool tool;

    @BeforeEach
    void setUp() {
        root = node("Test Plan");
        JMeterTreeNode threadGroup = node("Thread Group");
        httpRequest = node("HTTP Request");
        root.add(threadGroup);
        threadGroup.add(httpRequest);
        ThreadGroup element = new ThreadGroup();
        element.setName("Real Thread Group");
        LoopController controller = new LoopController();
        controller.setLoops(1);
        element.setSamplerController(controller);
        realThreadGroup = new JMeterTreeNode(element, null);
        root.add(realThreadGroup);
        // Mirror JMeter: the real Test Plan is the child of an internal wrapper root.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);
        updater = new FakeUpdater();
        tool = new UpdateElementPropertyHandler(() -> wrapperRoot, new ElementIdResolver(), updater).tool();
    }

    private static Map<String, Object> args(Object id, Object property, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", id);
        map.put("property", property);
        map.put("value", value);
        return map;
    }

    @Test
    void spec_declaresThreeRequiredParameters() {
        assertEquals(UpdateElementPropertyHandler.UPDATE_ELEMENT_PROPERTY, tool.getSpec().getName());
        assertEquals(3, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void update_validRequest_delegatesAndReportsSuccess() {
        ToolResult r = tool.execute(
                args("Test Plan/Thread Group/HTTP Request", "HTTPSampler.path", "/login"));
        assertTrue(r.isSuccess());
        assertSame(httpRequest, updater.lastNode);
        assertEquals("HTTPSampler.path", updater.lastProperty);
        assertEquals("/login", updater.lastValue);
        assertTrue(r.getData().contains("HTTPSampler.path"));
    }

    @Test
    void update_emptyValue_isAllowedAndPassedThrough() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", "HTTPSampler.path", ""));
        assertTrue(r.isSuccess());
        assertEquals("", updater.lastValue);
    }

    @Test
    void update_blankProperty_returnsInvalidPropertyError() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", "   ", "x"));
        assertFalse(r.isSuccess());
        assertEquals(UpdateElementPropertyHandler.ERR_INVALID_PROPERTY, r.getErrorCode());
    }

    @Test
    void update_elementNotFound_returnsError() {
        ToolResult r = tool.execute(args("Test Plan/Missing", "HTTPSampler.path", "/x"));
        assertFalse(r.isSuccess());
        assertEquals(UpdateElementPropertyHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void update_noTestPlan_returnsError() {
        Tool noPlan = new UpdateElementPropertyHandler(() -> null, new ElementIdResolver(), updater).tool();
        ToolResult r = noPlan.execute(args("Test Plan", "HTTPSampler.path", "/x"));
        assertFalse(r.isSuccess());
        assertEquals(UpdateElementPropertyHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void update_whenUpdaterFails_returnsUpdateFailedError() {
        updater.succeed = false;
        ToolResult r = tool.execute(
                args("Test Plan/Thread Group/HTTP Request", "HTTPSampler.path", "/x"));
        assertFalse(r.isSuccess());
        assertEquals(UpdateElementPropertyHandler.ERR_UPDATE_FAILED, r.getErrorCode());
    }

    @Test
    void update_unknownThreadGroupLoops_returnsHintWithoutUpdating() {
        ToolResult r = tool.execute(args("Test Plan/Real Thread Group", "ThreadGroup.loops", "2"));

        assertFalse(r.isSuccess());
        assertEquals(UpdateElementPropertyHandler.ERR_INVALID_PROPERTY, r.getErrorCode());
        assertTrue(r.getMessage().contains("LoopController.loops"));
        assertNull(updater.lastNode);
    }

    @Test
    void update_nestedLoopControllerProperty_delegatesToUpdater() {
        ToolResult r = tool.execute(args("Test Plan/Real Thread Group", "LoopController.loops", "2"));

        assertTrue(r.isSuccess());
        assertSame(realThreadGroup, updater.lastNode);
        assertEquals("LoopController.loops", updater.lastProperty);
    }

    @Test
    void update_catalogPropertyAbsentOnThreadGroup_delegatesToUpdater() {
        ToolResult r = tool.execute(args("Test Plan/Real Thread Group", "ThreadGroup.num_threads", "2"));

        assertTrue(r.isSuccess());
        assertSame(realThreadGroup, updater.lastNode);
        assertEquals("ThreadGroup.num_threads", updater.lastProperty);
    }

    @Test
    void update_brandNewConfigProperty_stillDelegatesToUpdater() {
        ToolResult r = tool.execute(
                args("Test Plan/Thread Group/HTTP Request", "some.brand_new_key", "value"));

        assertTrue(r.isSuccess());
        assertSame(httpRequest, updater.lastNode);
        assertEquals("some.brand_new_key", updater.lastProperty);
    }
}
