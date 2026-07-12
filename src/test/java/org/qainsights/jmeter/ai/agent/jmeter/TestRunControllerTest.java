package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link TestRunController#live()}. */
class TestRunControllerTest {

    @Test
    void live_noGuiPackage_returnsFalseWithoutThrowing() {
        // No live JMeter GUI in a plain test JVM, so GuiPackage.getInstance() is null.
        assertNull(GuiPackage.getInstance());

        TestRunController controller = TestRunController.live();

        assertFalse(controller.dispatch(ActionNames.ACTION_START));
        assertFalse(controller.dispatch(ActionNames.ACTION_STOP));
        assertFalse(controller.dispatch(ActionNames.ACTION_SHUTDOWN));
    }
}
