package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ReadToolHandlers}, using an in-memory tree (no GuiPackage). */
class ReadToolHandlersTest {

    private JMeterTreeNode root;
    private ReadToolHandlers handlers;

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @BeforeEach
    void setUp() {
        root = node("Test Plan");
        JMeterTreeNode threadGroup = node("Thread Group");
        JMeterTreeNode sampler = node("HTTP Request");
        sampler.getTestElement().setProperty("HTTPSampler.domain", "example.com");
        root.add(threadGroup);
        threadGroup.add(sampler);
        // Mirror JMeter: the real Test Plan is the child of an internal wrapper
        // root, which the read tools treat as the (excluded, unrendered) origin.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);
        handlers = new ReadToolHandlers(() -> wrapperRoot, new ElementIdResolver(), new SchemaGrounding());
    }

    private Tool toolNamed(String name) {
        for (Tool tool : handlers.tools()) {
            if (tool.getSpec().getName().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found: " + name);
    }

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    void tools_exposesTheFourReadTools() {
        List<Tool> tools = handlers.tools();
        assertEquals(4, tools.size());
    }

    @Test
    void getTreeState_defaultDepth_includesAllNodes() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_TREE_STATE).execute(Collections.emptyMap());
        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("Test Plan/Thread Group/HTTP Request"));
    }

    @Test
    void getTreeState_depthOne_hidesDeepNodes() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_TREE_STATE).execute(args("depth", 1));
        assertTrue(r.isSuccess());
        assertFalse(r.getData().contains("HTTP Request"));
        assertTrue(r.getData().contains("hidden"));
    }

    @Test
    void getTreeState_noTestPlan_returnsError() {
        ReadToolHandlers empty = new ReadToolHandlers(() -> null, new ElementIdResolver(), new SchemaGrounding());
        ToolResult r = empty.tools().get(0).execute(Collections.emptyMap());
        assertFalse(r.isSuccess());
        assertEquals(ReadToolHandlers.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void getElementConfig_returnsProperties() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_ELEMENT_CONFIG)
                .execute(args("element_id", "Test Plan/Thread Group/HTTP Request"));
        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("HTTPSampler.domain = example.com"));
    }

    @Test
    void getElementConfig_unknownId_returnsNotFound() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_ELEMENT_CONFIG).execute(args("element_id", "Test Plan/Nope"));
        assertFalse(r.isSuccess());
        assertEquals(ReadToolHandlers.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void getElementChildren_listsChildrenWithIds() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_ELEMENT_CHILDREN).execute(args("element_id", "Test Plan"));
        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("Thread Group"));
        assertTrue(r.getData().contains("id=Test Plan/Thread Group"));
    }

    @Test
    void getElementChildren_leafNode_reportsNoChildren() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_ELEMENT_CHILDREN)
                .execute(args("element_id", "Test Plan/Thread Group/HTTP Request"));
        assertTrue(r.isSuccess());
        assertEquals("No children.", r.getData());
    }

    @Test
    void getElementSchema_knownType_succeeds() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_ELEMENT_SCHEMA).execute(args("element_type", "HTTPSamplerProxy"));
        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("HTTPSamplerProxy"));
    }

    @Test
    void getElementSchema_unknownType_returnsError() {
        ToolResult r = toolNamed(ReadToolHandlers.GET_ELEMENT_SCHEMA).execute(args("element_type", "Nope"));
        assertFalse(r.isSuccess());
        assertEquals(ReadToolHandlers.ERR_UNKNOWN_ELEMENT_TYPE, r.getErrorCode());
    }
}
