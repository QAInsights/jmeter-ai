package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommandDispatcherFixCommandTest {
    @Mock
    private CommandCallback callback;

    @Test
    void fixCommandShowsPlanGuidanceWhenNoPlanIsOpen() {
        new CommandDispatcher(callback, ignored -> null).dispatch("@fix");

        verify(callback).appendRedMessage("Open a JMeter test plan before using @fix.");
    }

    @Test
    void runningTestIsRejectedBeforePlanConfirmationOrBackup() {
        TestRunController running = new TestRunController() {
            @Override
            public boolean dispatch(String actionName) {
                return false;
            }

            @Override
            public boolean isRunning() {
                return true;
            }
        };

        new FixCommandRunner(callback, ignored -> null, running).run();

        verify(callback).appendRedMessage("A test is already running. Stop it before using @fix.");
    }

    @Test
    void emptyPlanShowsPrerequisiteBeforeHostConfirmation() {
        assertEquals("This plan has no requests yet. Record a session or add an HTTP Request first.",
                FixCommandRunner.planPrerequisite(true, 0));
        assertEquals("Open a JMeter test plan before using @fix.",
                FixCommandRunner.planPrerequisite(false, 0));
        assertNull(FixCommandRunner.planPrerequisite(true, 1));
    }
}
