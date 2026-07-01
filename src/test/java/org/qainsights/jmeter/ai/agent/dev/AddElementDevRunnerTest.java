package org.qainsights.jmeter.ai.agent.dev;

import java.util.ArrayDeque;
import java.util.Deque;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementAdder;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.schema.SchemaGrounding;
import org.qainsights.jmeter.ai.agent.tool.handlers.AddElementHandler;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AddElementDevRunner} using fakes for all UI seams. */
class AddElementDevRunnerTest {

    private static final class FakeAdder implements ElementAdder {
        boolean succeed = true;

        @Override
        public JMeterTreeNode add(JMeterTreeNode parent, String addAlias, String name) {
            if (!succeed) {
                return null;
            }
            JMeterTreeNode child = node(name == null ? addAlias : name);
            parent.add(child);
            return child;
        }
    }

    /** Returns queued answers in order; used for the two prompts. */
    private static final class QueuePrompter implements AddElementDevRunner.Prompter {
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
    private JMeterTreeNode threadGroup;
    private FakeAdder adder;
    private AddElementDevRunner runner;
    private String shown;

    @BeforeEach
    void setUp() {
        root = node("Test Plan");
        threadGroup = node("Thread Group");
        root.add(threadGroup);
        // Mirror JMeter: the real Test Plan is the child of an internal wrapper root.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);
        adder = new FakeAdder();
        AddElementHandler handler = new AddElementHandler(() -> wrapperRoot, new ElementIdResolver(),
                new SchemaGrounding(), adder);
        runner = new AddElementDevRunner(handler, new ElementIdResolver());
        shown = null;
    }

    private AddElementDevRunner.Notifier capture() {
        return msg -> shown = msg;
    }

    @Test
    void run_noSelection_showsHintAndReturnsIt() {
        String msg = runner.run(() -> null, new QueuePrompter(), capture());
        assertTrue(msg.contains("Select a node"));
        assertEquals(msg, shown);
    }

    @Test
    void run_cancelledTypePrompt_returnsNull() {
        QueuePrompter prompter = new QueuePrompter(); // empty -> returns null
        assertNull(runner.run(() -> threadGroup, prompter, capture()));
        assertNull(shown);
    }

    @Test
    void run_validInput_invokesAddAndShowsSuccess() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("HTTPSamplerProxy");
        prompter.answers.add("Login");

        String msg = runner.run(() -> threadGroup, prompter, capture());

        assertTrue(msg.startsWith("add_element OK"));
        assertTrue(msg.contains("Test Plan/Thread Group/Login"));
        assertEquals(msg, shown);
    }

    @Test
    void run_blankName_usesDefaultAndStillSucceeds() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("ConstantTimer");
        prompter.answers.add("   ");

        String msg = runner.run(() -> threadGroup, prompter, capture());
        assertTrue(msg.startsWith("add_element OK"));
    }

    @Test
    void run_unknownType_showsFailureWithErrorCode() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("NotAType");
        prompter.answers.add("");

        String msg = runner.run(() -> threadGroup, prompter, capture());
        assertTrue(msg.contains("add_element FAILED"));
        assertTrue(msg.contains(AddElementHandler.ERR_UNKNOWN_ELEMENT_TYPE));
    }

    @Test
    void run_adderFailure_showsAddFailedError() {
        adder.succeed = false;
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("HTTPSamplerProxy");
        prompter.answers.add("X");

        String msg = runner.run(() -> threadGroup, prompter, capture());
        assertTrue(msg.contains(AddElementHandler.ERR_ADD_FAILED));
    }
}
