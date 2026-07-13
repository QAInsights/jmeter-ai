package org.qainsights.jmeter.ai.agent.dev;

import java.util.ArrayDeque;
import java.util.Deque;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementEnabler;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.tool.handlers.ToggleElementHandler;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToggleElementDevRunner} using fakes for all UI seams. */
class ToggleElementDevRunnerTest {

    private static final class FakeEnabler implements ElementEnabler {
        boolean succeed = true;
        boolean called;
        boolean lastEnabled;

        @Override
        public boolean setEnabled(JMeterTreeNode node, boolean enabled) {
            this.called = true;
            this.lastEnabled = enabled;
            return succeed;
        }
    }

    private static final class QueuePrompter implements ToggleElementDevRunner.Prompter {
        final Deque<String> answers = new ArrayDeque<>();

        @Override
        public String prompt(String label, String defaultValue) {
            return answers.isEmpty() ? null : answers.removeFirst();
        }
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    private JMeterTreeNode target;
    private FakeEnabler enabler;
    private ToggleElementDevRunner runner;
    private String shown;

    @BeforeEach
    void setUp() {
        JMeterTreeNode testPlan = node("Test Plan");
        target = node("HTTP Request");
        testPlan.add(target);
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);
        enabler = new FakeEnabler();
        ToggleElementHandler handler =
                new ToggleElementHandler(() -> wrapperRoot, new ElementIdResolver(), enabler);
        runner = new ToggleElementDevRunner(handler, new ElementIdResolver());
        shown = null;
    }

    private ToggleElementDevRunner.Notifier capture() {
        return msg -> shown = msg;
    }

    @Test
    void run_noSelection_showsHintAndReturnsIt() {
        String msg = runner.run(() -> null, new QueuePrompter(), capture());
        assertTrue(msg.contains("Select the element"));
        assertEquals(msg, shown);
    }

    @Test
    void run_cancelledPrompt_returnsNull() {
        assertNull(runner.run(() -> target, new QueuePrompter(), capture()));
        assertNull(shown);
    }

    @Test
    void run_emptyPrompt_returnsNull() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("   ");
        assertNull(runner.run(() -> target, prompter, capture()));
        assertNull(shown);
    }

    @Test
    void run_enableTrue_invokesEnablerAndShowsSuccess() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("true");

        String msg = runner.run(() -> target, prompter, capture());

        assertTrue(msg.startsWith("toggle_element OK"));
        assertTrue(enabler.called);
        assertTrue(enabler.lastEnabled);
        assertEquals(msg, shown);
    }

    @Test
    void run_enableFalse_invokesEnablerAndShowsSuccess() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("false");

        String msg = runner.run(() -> target, prompter, capture());

        assertTrue(msg.startsWith("toggle_element OK"));
        assertTrue(enabler.called);
        assertFalse(enabler.lastEnabled);
    }

    @Test
    void run_enablerFails_showsFailedError() {
        enabler.succeed = false;
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("true");

        String msg = runner.run(() -> target, prompter, capture());
        assertTrue(msg.contains(ToggleElementHandler.ERR_TOGGLE_FAILED));
    }

    @Test
    void run_invalidBoolean_showsInvalidStateError() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("maybe");

        String msg = runner.run(() -> target, prompter, capture());
        assertTrue(msg.contains(ToggleElementHandler.ERR_INVALID_STATE));
        assertFalse(enabler.called);
    }
}
