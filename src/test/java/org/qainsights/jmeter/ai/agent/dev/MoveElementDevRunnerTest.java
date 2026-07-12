package org.qainsights.jmeter.ai.agent.dev;

import java.util.ArrayDeque;
import java.util.Deque;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.ElementIdResolver;
import org.qainsights.jmeter.ai.agent.jmeter.ElementMover;
import org.qainsights.jmeter.ai.agent.tool.handlers.MoveElementHandler;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link MoveElementDevRunner} using fakes for all UI seams. */
class MoveElementDevRunnerTest {

    private static final class FakeMover implements ElementMover {
        boolean succeed = true;
        boolean called;
        JMeterTreeNode lastNode;
        JMeterTreeNode lastNewParent;

        @Override
        public boolean move(JMeterTreeNode node, JMeterTreeNode newParent) {
            this.called = true;
            this.lastNode = node;
            this.lastNewParent = newParent;
            return succeed;
        }
    }

    private static final class QueuePrompter implements MoveElementDevRunner.Prompter {
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

    private JMeterTreeNode testPlan;
    private JMeterTreeNode threadGroup;
    private JMeterTreeNode target;
    private JMeterTreeNode destParent;
    private FakeMover mover;
    private MoveElementDevRunner runner;
    private String shown;

    @BeforeEach
    void setUp() {
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        testPlan.add(threadGroup);
        target = node("HTTP Request");
        threadGroup.add(target);
        destParent = node("Thread Group 2");
        testPlan.add(destParent);
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(testPlan);
        mover = new FakeMover();
        MoveElementHandler handler =
                new MoveElementHandler(() -> wrapperRoot, new ElementIdResolver(), mover);
        runner = new MoveElementDevRunner(handler, new ElementIdResolver());
        shown = null;
    }

    private MoveElementDevRunner.Notifier capture() {
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
    void run_validInput_invokesMoveAndShowsSuccess() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("Test Plan/Thread Group 2");

        String msg = runner.run(() -> target, prompter, capture());

        assertTrue(msg.startsWith("move_element OK"));
        assertTrue(mover.called);
        assertEquals(target, mover.lastNode);
        assertEquals(destParent, mover.lastNewParent);
        assertEquals(msg, shown);
    }

    @Test
    void run_moverFails_showsFailedError() {
        mover.succeed = false;
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("Test Plan/Thread Group 2");

        String msg = runner.run(() -> target, prompter, capture());
        assertTrue(msg.contains(MoveElementHandler.ERR_MOVE_FAILED));
    }

    @Test
    void run_parentNotFound_showsParentNotFoundError() {
        QueuePrompter prompter = new QueuePrompter();
        prompter.answers.add("Test Plan/Nonexistent");

        String msg = runner.run(() -> target, prompter, capture());
        assertTrue(msg.contains(MoveElementHandler.ERR_PARENT_NOT_FOUND));
        assertFalse(mover.called);
    }
}
