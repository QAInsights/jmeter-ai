package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.ElementReorderer;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ReorderElementHandler} using an in-memory tree and fake reorderer. */
class ReorderElementHandlerTest {

    /** Records the call and, when enabled, actually repositions so ids resolve afresh. */
    private static final class FakeReorderer implements ElementReorderer {
        JMeterTreeNode lastNode;
        int lastNewIndex = -1;
        boolean succeed = true;

        @Override
        public boolean reorder(JMeterTreeNode node, int newIndex) {
            this.lastNode = node;
            this.lastNewIndex = newIndex;
            if (!succeed) {
                return false;
            }
            JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
            parent.remove(node);
            parent.insert(node, newIndex);
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
    private JMeterTreeNode samplerA;
    private JMeterTreeNode samplerB;
    private JMeterTreeNode samplerC;
    private FakeReorderer reorderer;
    private Tool tool;

    @BeforeEach
    void setUp() {
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        samplerA = node("Sampler A");
        samplerB = node("Sampler B");
        samplerC = node("Sampler C");
        testPlan.add(threadGroup);
        threadGroup.add(samplerA);
        threadGroup.add(samplerB);
        threadGroup.add(samplerC);
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);
        reorderer = new FakeReorderer();
        tool = new ReorderElementHandler(() -> wrapperRoot, new ElementIdResolver(), reorderer).tool();
    }

    private static Map<String, Object> args(Object id, Object newIndex) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", id);
        map.put("new_index", newIndex);
        return map;
    }

    @Test
    void spec_declaresNameAndTwoRequiredParameters() {
        assertEquals(ReorderElementHandler.REORDER_ELEMENT, tool.getSpec().getName());
        assertEquals(2, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void reorder_moveToEnd_delegatesAndReportsNewId() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Sampler A", 2));

        assertTrue(r.isSuccess());
        assertSame(samplerA, reorderer.lastNode);
        assertEquals(2, reorderer.lastNewIndex);
        assertEquals(2, threadGroup.getIndex(samplerA));
        assertTrue(r.getData().contains("index 2"));
    }

    @Test
    void reorder_moveToFront_movesFromLastToFirst() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Sampler C", 0));

        assertTrue(r.isSuccess());
        assertEquals(0, reorderer.lastNewIndex);
        assertEquals(0, threadGroup.getIndex(samplerC));
    }

    @Test
    void reorder_testPlan_isRejected() {
        ToolResult r = tool.execute(args("Test Plan", 0));

        assertFalse(r.isSuccess());
        assertEquals(ReorderElementHandler.ERR_CANNOT_REORDER_ROOT, r.getErrorCode());
        assertNull(reorderer.lastNode);
    }

    @Test
    void reorder_unknownElement_returnsNotFound() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Nope", 0));

        assertFalse(r.isSuccess());
        assertEquals(ReorderElementHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void reorder_missingNewIndex_returnsInvalidIndexWithoutDelegating() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Sampler A", null));

        assertFalse(r.isSuccess());
        assertEquals(ReorderElementHandler.ERR_INVALID_INDEX, r.getErrorCode());
        assertNull(reorderer.lastNode);
    }

    @Test
    void reorder_negativeIndex_returnsInvalidIndexWithoutDelegating() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Sampler A", -1));

        assertFalse(r.isSuccess());
        assertEquals(ReorderElementHandler.ERR_INVALID_INDEX, r.getErrorCode());
        assertNull(reorderer.lastNode);
    }

    @Test
    void reorder_indexOutOfBounds_returnsInvalidIndexWithoutDelegating() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Sampler A", 3));

        assertFalse(r.isSuccess());
        assertEquals(ReorderElementHandler.ERR_INVALID_INDEX, r.getErrorCode());
        assertNull(reorderer.lastNode);
    }

    @Test
    void reorder_noTestPlan_returnsError() {
        Tool noPlan = new ReorderElementHandler(() -> null, new ElementIdResolver(), reorderer).tool();
        ToolResult r = noPlan.execute(args("Test Plan/Thread Group/Sampler A", 0));

        assertFalse(r.isSuccess());
        assertEquals(ReorderElementHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void reorder_whenReordererFails_returnsReorderFailedError() {
        reorderer.succeed = false;
        ToolResult r = tool.execute(args("Test Plan/Thread Group/Sampler A", 1));

        assertFalse(r.isSuccess());
        assertEquals(ReorderElementHandler.ERR_REORDER_FAILED, r.getErrorCode());
    }
}
