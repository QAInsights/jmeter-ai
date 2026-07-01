package org.qainsights.jmeter.ai.agent.dev;

import java.util.ArrayDeque;
import java.util.Deque;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.PropertyUpdater;
import org.qainsights.jmeter.ai.agent.tool.handlers.UpdateElementPropertyHandler;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link UpdateElementPropertyDevRunner} using fakes for all UI seams. */
class UpdateElementPropertyDevRunnerTest {

    private static final class FakeUpdater implements PropertyUpdater {
        boolean succeed = true;
        String lastProperty;
        String lastValue;

        @Override
        public boolean update(JMeterTreeNode node, String property, String value) {
            this.lastProperty = property;
            this.lastValue = value;
            return succeed;
        }
    }

    private static final class QueuePrompter implements UpdateElementPropertyDevRunner.Prompter {
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

    private JMeterTreeNode root;
    private JMeterTreeNode target;
    private FakeUpdater updater;
    private UpdateElementPropertyDevRunner runner;
    private String shown;

    @BeforeEach
    void setUp() {
        root = node("Test Plan");
        target = node("HTTP Request");
        root.add(target);
        // Mirror JMeter: the real Test Plan is the child of an internal wrapper root.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);
        updater = new FakeUpdater();
        UpdateElementPropertyHandler handler =
                new UpdateElementPropertyHandler(() -> wrapperRoot, new ElementIdResolver(), updater);
        runner = new UpdateElementPropertyDevRunner(handler, new ElementIdResolver());
        shown = null;
    }

    private UpdateElementPropertyDevRunner.Notifier capture() {
        return msg -> shown = msg;
    }

    @Test
    void run_noSelection_showsHintAndReturnsIt() {
        String msg = runner.run(() -> null, new QueuePrompter(), capture());
        assertTrue(msg.contains("Select the element"));
        assertEquals(msg, shown);
    }

    @Test
    void run_cancelledPropertyPrompt_returnsNull() {
        assertNull(runner.run(() -> target, new QueuePrompter(), capture()));
        assertNull(shown);
    }

    @Test
    void run_cancelledValuePrompt_returnsNull() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("HTTPSampler.path"); // property answered, value cancelled (null)
        assertNull(runner.run(() -> target, prompter, capture()));
    }

    @Test
    void run_validInput_invokesUpdateAndShowsSuccess() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("HTTPSampler.path");
        prompter.answers.add("/login");

        String msg = runner.run(() -> target, prompter, capture());

        assertTrue(msg.startsWith("update_element_property OK"));
        assertEquals("HTTPSampler.path", updater.lastProperty);
        assertEquals("/login", updater.lastValue);
        assertEquals(msg, shown);
    }

    @Test
    void run_updaterFailure_showsFailedError() {
        updater.succeed = false;
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("HTTPSampler.path");
        prompter.answers.add("/x");

        String msg = runner.run(() -> target, prompter, capture());
        assertTrue(msg.contains(UpdateElementPropertyHandler.ERR_UPDATE_FAILED));
    }
}
