package org.qainsights.jmeter.ai.pet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the AI-chat busy animation in {@link PetAnimator}: the pet
 * runs while the AI thinks, and the busy/test-run flags compose correctly
 * (ending one must never kill the other's animation).
 */
class PetAnimatorBusyTest {

    private static PetAnimator newAnimator() {
        return new PetAnimator(state -> 6); // every state has frames
    }

    /** Runs out any one-shot overlay so the base state shows through. */
    private static void runOutOneShot(PetAnimator animator) {
        for (int i = 0; i < 6 * PetAnimator.ONE_SHOT_LOOPS + 1; i++) {
            animator.tick();
        }
    }

    @Test
    void busyStartReviewsAndBusyEndIdles() {
        PetAnimator animator = newAnimator();
        animator.onBusyStarted();
        // excitement burst first, then review mode
        assertEquals(PetState.JUMPING, animator.currentState());
        runOutOneShot(animator);
        assertEquals(PetState.REVIEW, animator.currentState());

        animator.onBusyEnded();
        assertEquals(PetState.IDLE, animator.currentState());
    }

    @Test
    void reviewFallsBackToRunningWhenMissing() {
        PetAnimator animator = new PetAnimator(
                state -> state == PetState.REVIEW ? 0 : 6);
        animator.onBusyStarted();
        runOutOneShot(animator);
        assertEquals(PetState.RUNNING, animator.currentState(),
                "pets without a REVIEW row fall back to RUNNING");
    }

    @Test
    void endingBusyKeepsRunningWhenTestRuns() {
        PetAnimator animator = newAnimator();
        animator.onTestStarted();
        runOutOneShot(animator);
        animator.onBusyStarted();
        animator.onBusyEnded();

        assertEquals(PetState.RUNNING, animator.currentState(),
                "the test's animation must survive the chat finishing");
    }

    @Test
    void endingTestKeepsReviewingWhenChatBusy() {
        PetAnimator animator = newAnimator();
        animator.onBusyStarted();
        runOutOneShot(animator); // into REVIEW
        animator.onTestStarted();
        runOutOneShot(animator); // test takes the stage
        assertEquals(PetState.RUNNING, animator.currentState());
        animator.onTestEnded();
        runOutOneShot(animator); // the wave plays out

        assertEquals(PetState.REVIEW, animator.currentState(),
                "the chat's review animation resumes after the test finishes");

        animator.onBusyEnded();
        assertEquals(PetState.IDLE, animator.currentState());
    }

    @Test
    void endingBothReturnsToIdle() {
        PetAnimator animator = newAnimator();
        animator.onTestStarted();
        animator.onBusyStarted();
        animator.onTestEnded();
        animator.onBusyEnded();
        runOutOneShot(animator);

        assertEquals(PetState.IDLE, animator.currentState());
    }

    @Test
    void bootstrapExposesAnimatorOnlyWhenStarted() {
        PetBootstrap.resetForTest();
        assertNull(PetBootstrap.animator(), "no animator before the pet starts");
    }
}
