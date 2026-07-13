package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.ElementMover;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link MoveElementHandler} using an in-memory tree and fake mover. */
class MoveElementHandlerTest {

    /** Records the call and, when enabled, actually reparents so ids resolve afresh. */
    private static final class FakeMover implements ElementMover {
        JMeterTreeNode lastNode;
        JMeterTreeNode lastNewParent;
        boolean succeed = true;

        @Override
        public boolean move(JMeterTreeNode node, JMeterTreeNode newParent) {
            this.lastNode = node;
            this.lastNewParent = newParent;
            if (!succeed) {
                return false;
            }
            newParent.add(node);
            return true;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode testPlan;
    private JMeterTreeNode threadGroup;
    private JMeterTreeNode threadGroup2;
    private JMeterTreeNode sampler;
    private FakeMover mover;
    private Tool tool;

    @BeforeEach
    void setUp() {
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        threadGroup2 = node("Thread Group 2");
        sampler = node("HTTP Request");
        testPlan.add(threadGroup);
        testPlan.add(threadGroup2);
        threadGroup.add(sampler);
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);
        mover = new FakeMover();
        tool = new MoveElementHandler(() -> wrapperRoot, new ElementIdResolver(), mover).tool();
    }

    private static Map<String, Object> args(Object id, Object newParentId) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", id);
        map.put("new_parent_id", newParentId);
        return map;
    }

    @Test
    void spec_declaresNameAndTwoRequiredParameters() {
        assertEquals(MoveElementHandler.MOVE_ELEMENT, tool.getSpec().getName());
        assertEquals(2, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void move_validRequest_delegatesAndReportsNewId() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", "Test Plan/Thread Group 2"));
        assertTrue(r.isSuccess());
        assertSame(sampler, mover.lastNode);
        assertSame(threadGroup2, mover.lastNewParent);
        assertTrue(r.getData().contains("Test Plan/Thread Group 2/HTTP Request"));
    }

    @Test
    void move_testPlan_isRejected() {
        ToolResult r = tool.execute(args("Test Plan", "Test Plan/Thread Group"));
        assertFalse(r.isSuccess());
        assertEquals(MoveElementHandler.ERR_CANNOT_MOVE_ROOT, r.getErrorCode());
    }

    @Test
    void move_unknownElement_returnsNotFound() {
        ToolResult r = tool.execute(args("Test Plan/Nope", "Test Plan/Thread Group 2"));
        assertFalse(r.isSuccess());
        assertEquals(MoveElementHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void move_unknownParent_returnsParentNotFound() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", "Test Plan/Nope"));
        assertFalse(r.isSuccess());
        assertEquals(MoveElementHandler.ERR_PARENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void move_noTestPlan_returnsError() {
        Tool noPlan = new MoveElementHandler(() -> null, new ElementIdResolver(), mover).tool();
        ToolResult r = noPlan.execute(args("Test Plan/Thread Group/HTTP Request", "Test Plan/Thread Group 2"));
        assertFalse(r.isSuccess());
        assertEquals(MoveElementHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void move_whenMoverFails_returnsMoveFailed() {
        mover.succeed = false;
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", "Test Plan/Thread Group 2"));
        assertFalse(r.isSuccess());
        assertEquals(MoveElementHandler.ERR_MOVE_FAILED, r.getErrorCode());
    }
}
