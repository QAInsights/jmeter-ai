package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link StopTestHandler} using an in-memory tree and fake controller. */
class StopTestHandlerTest {

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
        tool = new StopTestHandler(() -> root, controller).tool();
    }

    private static Map<String, Object> args(Object force) {
        Map<String, Object> map = new HashMap<>();
        map.put("force", force);
        return map;
    }

    @Test
    void spec_hasNoRequiredParameters() {
        assertEquals(StopTestHandler.STOP_TEST, tool.getSpec().getName());
        assertTrue(tool.getSpec().getRequiredParameters().isEmpty());
    }

    @Test
    void stop_defaultForce_dispatchesActionStop() {
        ToolResult r = tool.execute(Collections.emptyMap());

        assertTrue(r.isSuccess());
        assertEquals(ActionNames.ACTION_STOP, controller.lastAction);
    }

    @Test
    void stop_forceTrue_dispatchesActionShutdown() {
        ToolResult r = tool.execute(args(true));

        assertTrue(r.isSuccess());
        assertEquals(ActionNames.ACTION_SHUTDOWN, controller.lastAction);
    }

    @Test
    void stop_forceFalse_dispatchesActionStop() {
        ToolResult r = tool.execute(args(false));

        assertTrue(r.isSuccess());
        assertEquals(ActionNames.ACTION_STOP, controller.lastAction);
    }

    @Test
    void stop_noTestPlan_returnsError() {
        Tool noPlan = new StopTestHandler(() -> null, controller).tool();
        ToolResult r = noPlan.execute(Collections.emptyMap());

        assertFalse(r.isSuccess());
        assertEquals(StopTestHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
        assertNull(controller.lastAction);
    }

    @Test
    void stop_whenDispatchFails_returnsDispatchFailedError() {
        controller.succeed = false;
        ToolResult r = tool.execute(Collections.emptyMap());

        assertFalse(r.isSuccess());
        assertEquals(StopTestHandler.ERR_DISPATCH_FAILED, r.getErrorCode());
    }
}
