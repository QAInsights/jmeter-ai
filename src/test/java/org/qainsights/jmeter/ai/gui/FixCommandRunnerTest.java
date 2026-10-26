package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixCommandRunnerTest {
    @Test
    void asksBeforeUndoWhenSamePlanTreeChangedAfterCompletion() {
        assertTrue(FixCommandRunner.needsUndoConfirmation(true, "completed-tree", "later-edits"));
        assertEquals("You changed the plan after Make it pass finished. Undo all also discards those changes.",
                FixCommandRunner.undoConfirmationMessage(true));
    }

    @Test
    void skipsUndoConfirmationWhenPlanAndTreeAreUnchanged() {
        assertFalse(FixCommandRunner.needsUndoConfirmation(true, "same-tree", "same-tree"));
    }

    @Test
    void treatsInterruptedExecutionFailureAsCancellation() {
        assertTrue(FixCommandRunner.isCancellationFailure(false,
                new IllegalStateException("provider failed", new InterruptedException("sleep interrupted"))));
    }

    @Test
    void treatsCancelledExecutionFailureAsCancellation() {
        assertTrue(FixCommandRunner.isCancellationFailure(true, new IllegalStateException("provider failed")));
    }

    @Test
    void leavesOtherExecutionFailuresAsErrors() {
        assertFalse(FixCommandRunner.isCancellationFailure(false, new IllegalStateException("provider failed")));
    }
}
