package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.PropertyListUpdater;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link SetPropertyListHandler} using an in-memory tree and fake updater. */
class SetPropertyListHandlerTest {

    /** Records the last update and toggles success. */
    private static final class FakeUpdater implements PropertyListUpdater {
        JMeterTreeNode lastNode;
        String lastProperty;
        List<String> lastValues;
        boolean succeed = true;

        @Override
        public boolean update(JMeterTreeNode node, String property, List<String> values) {
            this.lastNode = node;
            this.lastProperty = property;
            this.lastValues = values;
            return succeed;
        }
    }

    private JMeterTreeNode root;
    private JMeterTreeNode assertionNode;
    private FakeUpdater updater;
    private Tool tool;

    @BeforeEach
    void setUp() {
        ConfigTestElement rootElement = new ConfigTestElement();
        rootElement.setName("Test Plan");
        root = new JMeterTreeNode(rootElement, null);

        ConfigTestElement threadGroupElement = new ConfigTestElement();
        threadGroupElement.setName("Thread Group");
        JMeterTreeNode threadGroup = new JMeterTreeNode(threadGroupElement, null);

        assertionNode = new JMeterTreeNode(new ResponseAssertion(), null);
        assertionNode.setName("Response Assertion");

        root.add(threadGroup);
        threadGroup.add(assertionNode);
        // Mirror JMeter: the real Test Plan is the child of an internal wrapper root.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);

        updater = new FakeUpdater();
        tool = new SetPropertyListHandler(() -> wrapperRoot, new ElementIdResolver(), updater).tool();
    }

    private static Map<String, Object> args(Object id, Object property, Object values) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", id);
        map.put("property", property);
        map.put("values", values);
        return map;
    }

    @Test
    void spec_declaresThreeRequiredParameters() {
        assertEquals(SetPropertyListHandler.SET_PROPERTY_LIST, tool.getSpec().getName());
        assertEquals(3, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void update_validRequest_delegatesAndReportsSuccess() {
        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/Response Assertion", "Asserion.test_strings", Arrays.asList("200", "201")));

        assertTrue(r.isSuccess());
        assertSame(assertionNode, updater.lastNode);
        assertEquals("Asserion.test_strings", updater.lastProperty);
        assertEquals(Arrays.asList("200", "201"), updater.lastValues);
    }

    @Test
    void update_unsupportedProperty_returnsUnsupportedPropertyErrorAndDoesNotDelegate() {
        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/Response Assertion", "HeaderManager.headers", Arrays.asList("x")));

        assertFalse(r.isSuccess());
        assertEquals(SetPropertyListHandler.ERR_UNSUPPORTED_PROPERTY, r.getErrorCode());
        assertNull(updater.lastNode);
    }

    @Test
    void update_blankProperty_returnsInvalidPropertyError() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Response Assertion", "   ", Arrays.asList("x")));
        assertFalse(r.isSuccess());
        assertEquals(SetPropertyListHandler.ERR_INVALID_PROPERTY, r.getErrorCode());
    }

    @Test
    void update_elementNotFound_returnsError() {
        ToolResult r = tool.execute(args("Test Plan/Missing", "Asserion.test_strings", Arrays.asList("x")));
        assertFalse(r.isSuccess());
        assertEquals(SetPropertyListHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void update_noTestPlan_returnsError() {
        Tool noPlan = new SetPropertyListHandler(() -> null, new ElementIdResolver(), updater).tool();
        ToolResult r = noPlan.execute(args("Test Plan", "Asserion.test_strings", Arrays.asList("x")));
        assertFalse(r.isSuccess());
        assertEquals(SetPropertyListHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void update_whenUpdaterFails_returnsUpdateFailedError() {
        updater.succeed = false;
        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/Response Assertion", "Asserion.test_strings", Arrays.asList("200")));
        assertFalse(r.isSuccess());
        assertEquals(SetPropertyListHandler.ERR_UPDATE_FAILED, r.getErrorCode());
    }

    @Test
    void update_emptyArray_isAllowedAndClearsList() {
        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/Response Assertion", "Asserion.test_strings", Arrays.asList()));
        assertTrue(r.isSuccess());
        assertTrue(updater.lastValues.isEmpty());
    }
}
