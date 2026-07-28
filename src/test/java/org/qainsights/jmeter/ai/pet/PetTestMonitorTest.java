package org.qainsights.jmeter.ai.pet;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetTestMonitor}.
 */
class PetTestMonitorTest {

    private static PetAnimator newAnimator() {
        return new PetAnimator(state -> 4);
    }

    private static void tickThroughOneShot(PetAnimator animator) {
        for (int i = 0; i < 4 * PetAnimator.ONE_SHOT_LOOPS; i++) {
            animator.tick();
        }
    }

    @Test
    void should_animateAndTapListeners_when_testStarts() {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetSampleTapTest.FakeListenerElement element = new PetSampleTapTest.FakeListenerElement();
        PetTestMonitor monitor = new PetTestMonitor(animator, tap,
                () -> Collections.singletonList(element));

        monitor.testStarted();

        assertEquals(PetState.JUMPING, animator.currentState());
        assertEquals(1, tap.tappedCount());
    }

    @Test
    void should_ignoreDuplicateStart_when_alreadyRunning() {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetTestMonitor monitor = new PetTestMonitor(animator, tap, Collections::emptyList);

        monitor.testStarted();
        tickThroughOneShot(animator);
        monitor.testStarted("localhost");

        assertEquals(PetState.RUNNING, animator.currentState());
    }

    @Test
    void should_untapAndCelebrate_when_cleanTestEnds() {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetSampleTapTest.FakeListenerElement element = new PetSampleTapTest.FakeListenerElement();
        PetTestMonitor monitor = new PetTestMonitor(animator, tap,
                () -> Collections.singletonList(element));

        monitor.testStarted();
        monitor.testEnded();

        assertEquals(0, tap.tappedCount());
        assertEquals(PetState.WAVING, animator.currentState());
    }

    @Test
    void should_frown_when_testEndsAfterFailures() {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetTestMonitor monitor = new PetTestMonitor(animator, tap, Collections::emptyList);

        monitor.testStarted();
        animator.onSampleFailure();
        monitor.testEnded("localhost");

        assertEquals(PetState.FAILED, animator.currentState());
    }

    @Test
    void should_ignoreEnd_when_noRunActive() {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetTestMonitor monitor = new PetTestMonitor(animator, tap, Collections::emptyList);

        monitor.testEnded();
        assertEquals(PetState.IDLE, animator.currentState());
    }

    @Test
    void should_surviveListenerSourceFailure_when_testStarts() {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetTestMonitor monitor = new PetTestMonitor(animator, tap, () -> {
            throw new IllegalStateException("no GUI");
        });

        assertDoesNotThrow(() -> monitor.testStarted());
        assertEquals(PetState.JUMPING, animator.currentState());
    }

    @Test
    void should_registerWithEngine_when_requested() {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetTestMonitor monitor = new PetTestMonitor(animator, tap, Collections::emptyList);
        assertDoesNotThrow(monitor::register);
    }

    @Test
    void should_reactToFailures_when_tappedVisualizerSeesFailedSample() throws Exception {
        PetAnimator animator = newAnimator();
        PetSampleTap tap = new PetSampleTap(animator::onSampleFailure);
        PetSampleTapTest.FakeListenerElement element = new PetSampleTapTest.FakeListenerElement();
        PetTestMonitor monitor = new PetTestMonitor(animator, tap,
                () -> List.of(element));

        monitor.testStarted();
        tickThroughOneShot(animator);

        org.apache.jmeter.samplers.SampleResult failed = new org.apache.jmeter.samplers.SampleResult();
        failed.setSuccessful(false);
        PetSampleTap.readVisualizer(element).add(failed);

        assertEquals(PetState.FAILED, animator.currentState());
    }
}
