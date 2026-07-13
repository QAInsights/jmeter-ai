package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementDeleter;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link DeleteElementHandler} using an in-memory tree and fake deleter. */
class DeleteElementHandlerTest {

    private static final class FakeDeleter implements ElementDeleter {
        JMeterTreeNode lastNode;
        boolean lastForce;
        boolean succeed = true;

        @Override
        public boolean delete(JMeterTreeNode node, boolean force) {
            this.lastNode = node;
            this.lastForce = force;
            return succeed;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode testPlan;
    private JMeterTreeNode threadGroup;
    private JMeterTreeNode sampler;
    private FakeDeleter deleter;
    private Tool tool;

    @BeforeEach
    void setUp() {
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        sampler = node("HTTP Request");
        testPlan.add(threadGroup);
        threadGroup.add(sampler);
        // Mirror JMeter: real Test Plan is the child of an internal wrapper root.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);
        deleter = new FakeDeleter();
        tool = new DeleteElementHandler(() -> wrapperRoot, new ElementIdResolver(), deleter).tool();
    }

    private static Map<String, Object> args(Object id, Object force) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", id);
        if (force != null) {
            map.put("force", force);
        }
        return map;
    }

    @Test
    void spec_declaresNameAndOneRequiredParameter() {
        assertEquals(DeleteElementHandler.DELETE_ELEMENT, tool.getSpec().getName());
        assertEquals(1, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void delete_leaf_succeedsWithoutForce() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", null));
        assertTrue(r.isSuccess());
        assertSame(sampler, deleter.lastNode);
        assertFalse(deleter.lastForce);
    }

    @Test
    void delete_nodeWithChildren_withoutForce_requiresConfirmation() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group", null));
        assertFalse(r.isSuccess());
        assertEquals(DeleteElementHandler.ERR_CONFIRM_REQUIRED, r.getErrorCode());
        assertNull(deleter.lastNode, "deleter must not be called when confirmation is required");
    }

    @Test
    void delete_nodeWithChildren_withForce_succeeds() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group", true));
        assertTrue(r.isSuccess());
        assertSame(threadGroup, deleter.lastNode);
        assertTrue(deleter.lastForce);
    }

    @Test
    void delete_testPlan_isRejected() {
        ToolResult r = tool.execute(args("Test Plan", true));
        assertFalse(r.isSuccess());
        assertEquals(DeleteElementHandler.ERR_CANNOT_DELETE_ROOT, r.getErrorCode());
    }

    @Test
    void delete_unknownId_returnsNotFound() {
        ToolResult r = tool.execute(args("Test Plan/Nope", null));
        assertFalse(r.isSuccess());
        assertEquals(DeleteElementHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void delete_noTestPlan_returnsError() {
        Tool noPlan = new DeleteElementHandler(() -> null, new ElementIdResolver(), deleter).tool();
        ToolResult r = noPlan.execute(args("Test Plan/Thread Group", null));
        assertFalse(r.isSuccess());
        assertEquals(DeleteElementHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void delete_whenDeleterFails_returnsDeleteFailed() {
        deleter.succeed = false;
        ToolResult r = tool.execute(args("Test Plan/Thread Group/HTTP Request", null));
        assertFalse(r.isSuccess());
        assertEquals(DeleteElementHandler.ERR_DELETE_FAILED, r.getErrorCode());
    }
}
