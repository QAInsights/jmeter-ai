package org.qainsights.jmeter.ai.agent.dev;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementDeleter;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.DeleteElementHandler;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link DeleteElementDevRunner} using fakes for all UI seams. */
class DeleteElementDevRunnerTest {

    private static final class FakeDeleter implements ElementDeleter {
        boolean succeed = true;
        boolean called;

        @Override
        public boolean delete(JMeterTreeNode node, boolean force) {
            this.called = true;
            return succeed;
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode target;
    private FakeDeleter deleter;
    private DeleteElementDevRunner runner;
    private String shown;

    @BeforeEach
    void setUp() {
        JMeterTreeNode testPlan = node("Test Plan");
        target = node("HTTP Request");
        testPlan.add(target);
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);
        deleter = new FakeDeleter();
        DeleteElementHandler handler =
                new DeleteElementHandler(() -> wrapperRoot, new ElementIdResolver(), deleter);
        runner = new DeleteElementDevRunner(handler, new ElementIdResolver());
        shown = null;
    }

    private DeleteElementDevRunner.Notifier capture() {
        return msg -> shown = msg;
    }

    @Test
    void run_noSelection_showsHintAndReturnsIt() {
        String msg = runner.run(() -> null, m -> true, capture());
        assertTrue(msg.contains("Select the element"));
        assertEquals(msg, shown);
    }

    @Test
    void run_userCancels_returnsNullAndDoesNotDelete() {
        assertNull(runner.run(() -> target, m -> false, capture()));
        assertFalse(deleter.called);
        assertNull(shown);
    }

    @Test
    void run_confirmed_invokesDeleteAndShowsSuccess() {
        String msg = runner.run(() -> target, m -> true, capture());
        assertTrue(msg.startsWith("delete_element OK"));
        assertTrue(deleter.called);
        assertEquals(msg, shown);
    }

    @Test
    void run_deleterFails_showsFailedError() {
        deleter.succeed = false;
        String msg = runner.run(() -> target, m -> true, capture());
        assertTrue(msg.contains(DeleteElementHandler.ERR_DELETE_FAILED));
    }
}
