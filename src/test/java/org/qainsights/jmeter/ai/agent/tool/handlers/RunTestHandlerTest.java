package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Collections;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link RunTestHandler} using an in-memory tree and fake controller. */
class RunTestHandlerTest {

    /** Records the last dispatched action and toggles success. */
    private static final class FakeController implements TestRunController {
        String lastAction;
        boolean succeed = true;

        @Override
        public boolean dispatch(String actionName) {
            this.lastAction = actionName;
            return succeed;
        }
    }

    private JMeterTreeNode root;
    private FakeController controller;
    private Tool tool;

    @BeforeEach
    void setUp() {
        ConfigTestElement rootElement = new ConfigTestElement();
        rootElement.setName("Test Plan");
        root = new JMeterTreeNode(rootElement, null);

        controller = new FakeController();
        tool = new RunTestHandler(() -> root, controller).tool();
    }

    @Test
    void spec_hasNoRequiredParameters() {
        assertEquals(RunTestHandler.RUN_TEST, tool.getSpec().getName());
        assertTrue(tool.getSpec().getRequiredParameters().isEmpty());
    }

    @Test
    void run_validRequest_dispatchesActionStartAndReportsSuccess() {
        ToolResult r = tool.execute(Collections.emptyMap());

        assertTrue(r.isSuccess());
        assertEquals(ActionNames.ACTION_START, controller.lastAction);
    }

    @Test
    void run_noTestPlan_returnsError() {
        Tool noPlan = new RunTestHandler(() -> null, controller).tool();
        ToolResult r = noPlan.execute(Collections.emptyMap());

        assertFalse(r.isSuccess());
        assertEquals(RunTestHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
        assertNull(controller.lastAction);
    }

    @Test
    void run_whenDispatchFails_returnsDispatchFailedError() {
        controller.succeed = false;
        ToolResult r = tool.execute(Collections.emptyMap());

        assertFalse(r.isSuccess());
        assertEquals(RunTestHandler.ERR_DISPATCH_FAILED, r.getErrorCode());
    }
}
