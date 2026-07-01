package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementAdder;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AddElementHandler} using an in-memory tree and fake adder. */
class AddElementHandlerTest {

    /** Records the last call and, when enabled, attaches a real child so ids resolve. */
    private static final class FakeAdder implements ElementAdder {
        JMeterTreeNode lastParent;
        String lastAlias;
        String lastName;
        boolean succeed = true;

        @Override
        public JMeterTreeNode add(JMeterTreeNode parent, String addAlias, String name) {
            this.lastParent = parent;
            this.lastAlias = addAlias;
            this.lastName = name;
            if (!succeed) {
                return null;
            }
            JMeterTreeNode child = node(name == null ? addAlias : name);
            parent.add(child);
            return child;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode root;
    private FakeAdder adder;
    private AddElementHandler handler;
    private Tool tool;

    @BeforeEach
    void setUp() {
        root = node("Test Plan");
        root.add(node("Thread Group"));
        // Mirror JMeter: the real Test Plan is the child of an internal wrapper root.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);
        adder = new FakeAdder();
        handler = new AddElementHandler(() -> wrapperRoot, new ElementIdResolver(), new SchemaGrounding(), adder);
        tool = handler.tool();
    }

    private static Map<String, Object> args(Object type, Object parentId, Object name) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_type", type);
        map.put("parent_id", parentId);
        if (name != null) {
            map.put("name", name);
        }
        return map;
    }

    @Test
    void spec_declaresNameAndRequiredParameters() {
        assertEquals(AddElementHandler.ADD_ELEMENT, tool.getSpec().getName());
        assertEquals(2, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void add_validRequest_delegatesWithAliasAndReturnsNewId() {
        ToolResult r = tool.execute(args("HTTPSamplerProxy", "Test Plan/Thread Group", "Login"));
        assertTrue(r.isSuccess());
        assertEquals("httpsampler", adder.lastAlias);
        assertEquals("Login", adder.lastName);
        assertSame(root.getChildAt(0), adder.lastParent);
        assertTrue(r.getData().contains("Test Plan/Thread Group/Login"));
    }

    @Test
    void add_withoutName_passesNullNameToAdder() {
        ToolResult r = tool.execute(args("ConstantTimer", "Test Plan/Thread Group", null));
        assertTrue(r.isSuccess());
        assertNull(adder.lastName);
    }

    @Test
    void add_blankName_isTreatedAsNull() {
        tool.execute(args("ConstantTimer", "Test Plan/Thread Group", "   "));
        assertNull(adder.lastName);
    }

    @Test
    void add_unknownElementType_returnsError() {
        ToolResult r = tool.execute(args("NotAType", "Test Plan/Thread Group", null));
        assertFalse(r.isSuccess());
        assertEquals(AddElementHandler.ERR_UNKNOWN_ELEMENT_TYPE, r.getErrorCode());
    }

    @Test
    void add_parentNotFound_returnsError() {
        ToolResult r = tool.execute(args("HTTPSamplerProxy", "Test Plan/Missing", null));
        assertFalse(r.isSuccess());
        assertEquals(AddElementHandler.ERR_PARENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void add_noTestPlan_returnsError() {
        AddElementHandler noPlan = new AddElementHandler(() -> null, new ElementIdResolver(),
                new SchemaGrounding(), adder);
        ToolResult r = noPlan.tool().execute(args("HTTPSamplerProxy", "Test Plan", null));
        assertFalse(r.isSuccess());
        assertEquals(AddElementHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void add_whenAdderFails_returnsAddFailedError() {
        adder.succeed = false;
        ToolResult r = tool.execute(args("HTTPSamplerProxy", "Test Plan/Thread Group", null));
        assertFalse(r.isSuccess());
        assertEquals(AddElementHandler.ERR_ADD_FAILED, r.getErrorCode());
    }
}
