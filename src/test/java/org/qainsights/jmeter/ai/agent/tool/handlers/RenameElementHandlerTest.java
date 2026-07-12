package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.ElementRenamer;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link RenameElementHandler} using an in-memory tree and fake renamer. */
class RenameElementHandlerTest {

    /**
     * Records the last request and toggles success. When {@code succeed} is
     * true, mimics the real renamer's side effect of actually renaming the
     * node - the handler resolves the post-rename id against the live tree
     * afterwards, so the name change must actually take effect.
     */
    private static final class FakeRenamer implements ElementRenamer {
        JMeterTreeNode lastNode;
        String lastNewName;
        boolean succeed = true;

        @Override
        public boolean rename(JMeterTreeNode node, String newName) {
            this.lastNode = node;
            this.lastNewName = newName;
            if (succeed) {
                node.setName(newName);
            }
            return succeed;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode wrapperRoot;
    private JMeterTreeNode testPlan;
    private JMeterTreeNode threadGroup;
    private FakeRenamer renamer;
    private Tool tool;

    @BeforeEach
    void setUp() {
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        testPlan.add(threadGroup);
        // Mirror JMeter's live tree: the real Test Plan is the child of an internal
        // wrapper root, which the resolver/handlers treat as the (excluded) origin.
        wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);

        renamer = new FakeRenamer();
        tool = new RenameElementHandler(() -> wrapperRoot, new ElementIdResolver(), renamer).tool();
    }

    private static Map<String, Object> args(String elementId, String newName) {
        Map<String, Object> map = new HashMap<>();
        map.put("element_id", elementId);
        map.put("new_name", newName);
        return map;
    }

    @Test
    void spec_declaresNameAndTwoRequiredParameters() {
        assertEquals(RenameElementHandler.RENAME_ELEMENT, tool.getSpec().getName());
        assertEquals(2, tool.getSpec().getRequiredParameters().size());
    }

    @Test
    void rename_validRequest_delegatesAndReportsNewId() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group", "Renamed Group"));

        assertTrue(r.isSuccess());
        assertSame(threadGroup, renamer.lastNode);
        assertEquals("Renamed Group", renamer.lastNewName);
        assertTrue(r.getData().contains("Test Plan/Renamed Group"));
    }

    @Test
    void rename_noTestPlan_returnsError() {
        Tool noPlan = new RenameElementHandler(() -> null, new ElementIdResolver(), renamer).tool();
        ToolResult r = noPlan.execute(args("Test Plan/Thread Group", "Renamed"));

        assertFalse(r.isSuccess());
        assertEquals(RenameElementHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
    }

    @Test
    void rename_unknownElementId_returnsError() {
        ToolResult r = tool.execute(args("Test Plan/Does Not Exist", "Renamed"));

        assertFalse(r.isSuccess());
        assertEquals(RenameElementHandler.ERR_ELEMENT_NOT_FOUND, r.getErrorCode());
    }

    @Test
    void rename_blankNewName_returnsInvalidNameErrorWithoutDelegating() {
        ToolResult r = tool.execute(args("Test Plan/Thread Group", "   "));

        assertFalse(r.isSuccess());
        assertEquals(RenameElementHandler.ERR_INVALID_NAME, r.getErrorCode());
        assertNull(renamer.lastNode);
    }

    @Test
    void rename_whenRenamerFails_returnsRenameFailedError() {
        renamer.succeed = false;
        ToolResult r = tool.execute(args("Test Plan/Thread Group", "Renamed"));

        assertFalse(r.isSuccess());
        assertEquals(RenameElementHandler.ERR_RENAME_FAILED, r.getErrorCode());
    }

    @Test
    void rename_testPlanRoot_isAllowed() {
        ToolResult r = tool.execute(args("Test Plan", "Renamed Plan"));

        assertTrue(r.isSuccess());
        assertSame(testPlan, renamer.lastNode);
    }
}
