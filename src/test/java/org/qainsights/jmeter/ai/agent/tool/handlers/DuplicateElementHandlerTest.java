package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Collections;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementDuplicator;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link DuplicateElementHandler} using an in-memory tree and fake duplicator. */
class DuplicateElementHandlerTest {

    /**
     * Records the last node passed in and toggles success. When {@code toReturn}
     * is set, mimics the real duplicator's side effect of inserting the clone as
     * a sibling of {@code node} - the handler resolves ids against the live tree
     * afterwards, so the clone must actually be attached for that to work.
     */
    private static final class FakeDuplicator implements ElementDuplicator {
        JMeterTreeNode lastRequested;
        JMeterTreeNode toReturn;

        @Override
        public JMeterTreeNode duplicate(JMeterTreeNode node) {
            this.lastRequested = node;
            if (toReturn != null && node.getParent() instanceof JMeterTreeNode) {
                ((JMeterTreeNode) node.getParent()).add(toReturn);
            }
            return toReturn;
        }
    }

    private JMeterTreeNode wrapperRoot;
    private JMeterTreeNode testPlan;
    private JMeterTreeNode threadGroup;
    private FakeDuplicator duplicator;
    private Tool tool;

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @BeforeEach
    void setUp() {
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        testPlan.add(threadGroup);
        // Mirror JMeter's live tree: the real Test Plan is the child of an internal
        // wrapper root, which the resolver/handlers treat as the (excluded) origin.
        wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);

        duplicator = new FakeDuplicator();
        tool = new DuplicateElementHandler(() -> wrapperRoot, new ElementIdResolver(), duplicator).tool();
    }

    private static java.util.Map<String, Object> args(String elementId) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("element_id", elementId);
        return map;
    }

    @Test
    void spec_requiresElementId() {
        assertEquals(DuplicateElementHandler.DUPLICATE_ELEMENT, tool.getSpec().getName());
        assertEquals(1, tool.getSpec().getRequiredParameters().size());
        assertEquals("element_id", tool.getSpec().getRequiredParameters().get(0).getName());
    }

    @Test
    void duplicate_validRequest_delegatesAndReportsNewId() {
        duplicator.toReturn = node("Thread Group");

        ToolResult r = tool.execute(args("Test Plan/Thread Group"));

        assertTrue(r.isSuccess());
        assertSame(threadGroup, duplicator.lastRequested);
        assertTrue(r.getData().contains("Test Plan/Thread Group[2]"));
    }

    @Test
    void duplicate_noTestPlan_returnsError() {
        Tool noPlan = new DuplicateElementHandler(() -> null, new ElementIdResolver(), duplicator).tool();
        ToolResult r = noPlan.execute(args("Test Plan/Thread Group"));

        assertFalse(r.isSuccess());
        assertEquals(DuplicateElementHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void duplicate_unknownElementId_returnsError() {
        ToolResult r = tool.execute(args("Test Plan/Does Not Exist"));

        assertFalse(r.isSuccess());
        assertEquals(DuplicateElementHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void duplicate_testPlanRoot_returnsError() {
        ToolResult r = tool.execute(args("Test Plan"));

        assertFalse(r.isSuccess());
        assertEquals(DuplicateElementHandler.ERR_CANNOT_DUPLICATE_ROOT, r.getErrorCode());
        assertNull(duplicator.lastRequested);
    }

    @Test
    void duplicate_whenDuplicatorFails_returnsDuplicateFailedError() {
        duplicator.toReturn = null;

        ToolResult r = tool.execute(args("Test Plan/Thread Group"));

        assertFalse(r.isSuccess());
        assertEquals(DuplicateElementHandler.ERR_DUPLICATE_FAILED, r.getErrorCode());
    }
}
