package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.StructuredPropertyListUpdater;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link SetStructuredPropertyListHandler} using an in-memory tree and fake updater. */
class SetStructuredPropertyListHandlerTest {

    /** Records the last update and toggles success. */
    private static final class FakeUpdater implements StructuredPropertyListUpdater {
        JMeterTreeNode lastNode;
        String lastProperty;
        List<Map<String, String>> lastEntries;
        boolean succeed = true;

        @Override
        public boolean update(JMeterTreeNode node, String property, List<Map<String, String>> entries) {
            this.lastNode = node;
            this.lastProperty = property;
            this.lastEntries = entries;
            return succeed;
        }
    }

    private JMeterTreeNode root;
    private JMeterTreeNode headerManagerNode;
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

        headerManagerNode = new JMeterTreeNode(new HeaderManager(), null);
        headerManagerNode.setName("HTTP Header Manager");

        root.add(threadGroup);
        threadGroup.add(headerManagerNode);
        // Mirror JMeter: the real Test Plan is the child of an internal wrapper root.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);

        updater = new FakeUpdater();
        tool = new SetStructuredPropertyListHandler(() -> wrapperRoot, new ElementIdResolver(), updater).tool();
    }

    private static Map<String, Object> args(Object id, Object property, Object entries) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", id);
        map.put("property", property);
        map.put("entries", entries);
        return map;
    }

    private static Map<String, String> nameValue(String name, String value) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }

    @Test
    void spec_declaresThreeRequiredParameters() {
        assertEquals(SetStructuredPropertyListHandler.SET_STRUCTURED_PROPERTY_LIST, tool.getSpec().getName());
        assertEquals(3, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void update_validRequest_delegatesAndReportsSuccess() {
        List<Map<String, String>> entries = Arrays.asList(
                nameValue("Content-Type", "application/json"), nameValue("Accept", "*/*"));

        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/HTTP Header Manager", "HeaderManager.headers", entries));

        assertTrue(r.isSuccess());
        assertSame(headerManagerNode, updater.lastNode);
        assertEquals("HeaderManager.headers", updater.lastProperty);
        assertEquals(entries, updater.lastEntries);
    }

    @Test
    void update_unsupportedProperty_returnsUnsupportedPropertyErrorAndDoesNotDelegate() {
        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/HTTP Header Manager", "Asserion.test_strings",
                Collections.singletonList(nameValue("x", "y"))));

        assertFalse(r.isSuccess());
        assertEquals(SetStructuredPropertyListHandler.ERR_UNSUPPORTED_PROPERTY, r.getErrorCode());
        assertNull(updater.lastNode);
    }

    @Test
    void update_blankProperty_returnsInvalidPropertyError() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Header Manager", "   ",
                Collections.singletonList(nameValue("x", "y"))));
        assertFalse(r.isSuccess());
        assertEquals(SetStructuredPropertyListHandler.ERR_INVALID_PROPERTY, r.getErrorCode());
    }

    @Test
    void update_elementNotFound_returnsError() {
        ToolResult r = tool.execute(args("Test Plan/Missing", "HeaderManager.headers",
                Collections.singletonList(nameValue("x", "y"))));
        assertFalse(r.isSuccess());
        assertEquals(SetStructuredPropertyListHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void update_noTestPlan_returnsError() {
        Tool noPlan = new SetStructuredPropertyListHandler(() -> null, new ElementIdResolver(), updater).tool();
        ToolResult r = noPlan.execute(args("Test Plan", "HeaderManager.headers",
                Collections.singletonList(nameValue("x", "y"))));
        assertFalse(r.isSuccess());
        assertEquals(SetStructuredPropertyListHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void update_whenUpdaterFails_returnsUpdateFailedError() {
        updater.succeed = false;
        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/HTTP Header Manager", "HeaderManager.headers",
                Collections.singletonList(nameValue("x", "y"))));
        assertFalse(r.isSuccess());
        assertEquals(SetStructuredPropertyListHandler.ERR_UPDATE_FAILED, r.getErrorCode());
    }

    @Test
    void update_emptyArray_isAllowedAndClearsList() {
        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/HTTP Header Manager", "HeaderManager.headers", new ArrayList<>()));
        assertTrue(r.isSuccess());
        assertTrue(updater.lastEntries.isEmpty());
    }

    @Test
    void update_nonMapEntriesAreDropped() {
        List<Object> entries = new ArrayList<>();
        entries.add(nameValue("A", "1"));
        entries.add("not-a-map");

        ToolResult r = tool.execute(args(
                "Test Plan/Thread Group/HTTP Header Manager", "HeaderManager.headers", entries));

        assertTrue(r.isSuccess());
        assertEquals(1, updater.lastEntries.size());
        assertEquals("A", updater.lastEntries.get(0).get("name"));
    }
}
