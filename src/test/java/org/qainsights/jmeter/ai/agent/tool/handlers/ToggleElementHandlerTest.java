package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementEnabler;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToggleElementHandler} using an in-memory tree and fake enabler. */
class ToggleElementHandlerTest {

    private static final class FakeEnabler implements ElementEnabler {
        JMeterTreeNode lastNode;
        Boolean lastEnabled;
        boolean succeed = true;

        @Override
        public boolean setEnabled(JMeterTreeNode node, boolean enabled) {
            this.lastNode = node;
            this.lastEnabled = enabled;
            return succeed;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode sampler;
    private FakeEnabler enabler;
    private Tool tool;

    @BeforeEach
    void setUp() {
        JMeterTreeNode testPlan = node("Test Plan");
        JMeterTreeNode threadGroup = node("Thread Group");
        sampler = node("HTTP Request");
        testPlan.add(threadGroup);
        threadGroup.add(sampler);
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);
        enabler = new FakeEnabler();
        tool = new ToggleElementHandler(() -> wrapperRoot, new ElementIdResolver(), enabler).tool();
    }

    private static Map<String, Object> args(Object id, Object enabled) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", id);
        if (enabled != null) {
            map.put("enabled", enabled);
        }
        return map;
    }

    @Test
    void spec_declaresNameAndTwoRequiredParameters() {
        assertEquals(ToggleElementHandler.TOGGLE_ELEMENT, tool.getSpec().getName());
        assertEquals(2, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void toggle_disable_delegatesWithFalse() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", false));
        assertTrue(r.isSuccess());
        assertSame(sampler, enabler.lastNode);
        assertEquals(Boolean.FALSE, enabler.lastEnabled);
        assertTrue(r.getData().startsWith("Disabled"));
    }

    @Test
    void toggle_enable_delegatesWithTrue() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", "true"));
        assertTrue(r.isSuccess());
        assertEquals(Boolean.TRUE, enabler.lastEnabled);
        assertTrue(r.getData().startsWith("Enabled"));
    }

    @Test
    void toggle_missingEnabled_returnsInvalidState() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", null));
        assertFalse(r.isSuccess());
        assertEquals(ToggleElementHandler.ERR_INVALID_STATE, r.getErrorCode());
        assertNull(enabler.lastNode);
    }

    @Test
    void toggle_unknownId_returnsNotFound() {
        ToolResult r = tool.execute(args("Test Plan/Nope", true));
        assertFalse(r.isSuccess());
        assertEquals(ToggleElementHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void toggle_noTestPlan_returnsError() {
        Tool noPlan = new ToggleElementHandler(() -> null, new ElementIdResolver(), enabler).tool();
        ToolResult r = noPlan.execute(args("Test Plan/Thread Group", true));
        assertFalse(r.isSuccess());
        assertEquals(ToggleElementHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void toggle_whenEnablerFails_returnsToggleFailed() {
        enabler.succeed = false;
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", true));
        assertFalse(r.isSuccess());
        assertEquals(ToggleElementHandler.ERR_TOGGLE_FAILED, r.getErrorCode());
    }
}
