package org.qainsights.jmeter.ai.pet;

import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetAnimator}.
 */
class PetAnimatorTest {

    private static final int FRAMES = 4;
    private static final ToIntFunction<PetState> UNIFORM = state -> FRAMES;

    private static void tickThroughOneShot(PetAnimator animator) {
        for (int i = 0; i < FRAMES * PetAnimator.ONE_SHOT_LOOPS; i++) {
            animator.tick();
        }
    }

    @Test
    void should_startIdle_when_created() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        assertEquals(PetState.IDLE, animator.currentState());
        assertEquals(0, animator.frameIndex());
    }

    @Test
    void should_advanceFrames_when_ticked() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.tick();
        animator.tick();
        assertEquals(2, animator.frameIndex());
    }

    @Test
    void should_jumpThenRun_when_testStarts() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onTestStarted();
        assertEquals(PetState.JUMPING, animator.currentState());
        tickThroughOneShot(animator);
        assertEquals(PetState.RUNNING, animator.currentState());
    }

    @Test
    void should_frownThenResumeRunning_when_sampleFailsMidRun() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onTestStarted();
        tickThroughOneShot(animator);
        animator.onSampleFailure();
        assertEquals(PetState.FAILED, animator.currentState());
        tickThroughOneShot(animator);
        assertEquals(PetState.RUNNING, animator.currentState());
    }

    @Test
    void should_ignoreSampleFailure_when_noTestRunning() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onSampleFailure();
        assertEquals(PetState.IDLE, animator.currentState());
    }

    @Test
    void should_waveThenIdle_when_cleanTestEnds() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onTestStarted();
        animator.onTestEnded();
        assertEquals(PetState.WAVING, animator.currentState());
        tickThroughOneShot(animator);
        assertEquals(PetState.IDLE, animator.currentState());
    }

    @Test
    void should_frownThenIdle_when_failedTestEnds() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onTestStarted();
        animator.onSampleFailure();
        animator.onTestEnded();
        assertEquals(PetState.FAILED, animator.currentState());
        tickThroughOneShot(animator);
        assertEquals(PetState.IDLE, animator.currentState());
    }

    @Test
    void should_resetFailureMemory_when_newTestStarts() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onTestStarted();
        animator.onSampleFailure();
        animator.onTestEnded();
        animator.onTestStarted();
        animator.onTestEnded();
        assertEquals(PetState.WAVING, animator.currentState());
    }

    @Test
    void should_faceDragDirection_when_dragged() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onDragStarted();
        assertEquals(PetState.RUNNING_RIGHT, animator.currentState());
        animator.onDragMoved(-5);
        assertEquals(PetState.RUNNING_LEFT, animator.currentState());
        animator.onDragMoved(3);
        assertEquals(PetState.RUNNING_RIGHT, animator.currentState());
    }

    @Test
    void should_resumeBaseState_when_dragEnds() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onTestStarted();
        tickThroughOneShot(animator);
        animator.onDragStarted();
        animator.onDragEnded();
        assertEquals(PetState.RUNNING, animator.currentState());
    }

    @Test
    void should_notInterruptDrag_when_oneShotEventArrives() {
        PetAnimator animator = new PetAnimator(UNIFORM);
        animator.onDragStarted();
        animator.onTestStarted();
        assertEquals(PetState.RUNNING_RIGHT, animator.currentState());
        animator.onDragEnded();
        assertEquals(PetState.RUNNING, animator.currentState());
    }

    @Test
    void should_fallBackGracefully_when_rowsHaveNoFrames() {
        ToIntFunction<PetState> onlyIdleAndRunning =
                state -> (state == PetState.IDLE || state == PetState.RUNNING) ? 3 : 0;
        PetAnimator animator = new PetAnimator(onlyIdleAndRunning);
        animator.onTestStarted();
        assertEquals(PetState.RUNNING, animator.currentState());
        animator.onDragStarted();
        assertEquals(PetState.IDLE, animator.currentState());
        animator.onDragEnded();
        animator.onTestEnded();
        assertEquals(PetState.IDLE, animator.currentState());
    }
}
