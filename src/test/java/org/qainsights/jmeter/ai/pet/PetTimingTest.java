package org.qainsights.jmeter.ai.pet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetTiming}.
 */
class PetTimingTest {

    @Test
    void should_returnIdleLongHold_when_queryingIdleFrames() {
        PetTiming timing = PetTiming.defaults();
        // idle is a uniform long hold (no quick flutter frames) so the loop breathes calmly
        for (int i = 0; i < timing.entryCount(PetState.IDLE); i++) {
            assertEquals(300, timing.durationMs(PetState.IDLE, i), "frame " + i + " should be a long hold");
        }
    }

    @Test
    void should_idleSlowerThanRunning_when_comparingFrameDurations() {
        PetTiming timing = PetTiming.defaults();
        assertTrue(timing.durationMs(PetState.IDLE, 0)
                        > timing.durationMs(PetState.RUNNING, 0),
                "idle long hold should be slower than running");
    }

    @Test
    void should_wrapIndex_when_frameIndexExceedsEntryCount() {
        PetTiming timing = PetTiming.defaults();
        int count = timing.entryCount(PetState.IDLE);
        assertEquals(timing.durationMs(PetState.IDLE, 0), timing.durationMs(PetState.IDLE, count));
        assertEquals(timing.durationMs(PetState.IDLE, 1), timing.durationMs(PetState.IDLE, count + 1));
    }

    @Test
    void should_handleNegativeIndex_when_wrapping() {
        PetTiming timing = PetTiming.defaults();
        int count = timing.entryCount(PetState.IDLE);
        assertEquals(timing.durationMs(PetState.IDLE, count - 1),
                timing.durationMs(PetState.IDLE, -1));
    }

    @Test
    void should_defineAllNineStates_when_inspectingDefaults() {
        PetTiming timing = PetTiming.defaults();
        for (PetState state : PetState.values()) {
            assertTrue(timing.entryCount(state) > 0, "no timing entries for " + state);
        }
    }

    @Test
    void should_returnPositiveDurations_when_queryingAllStates() {
        PetTiming timing = PetTiming.defaults();
        for (PetState state : PetState.values()) {
            for (int i = 0; i < timing.entryCount(state); i++) {
                assertTrue(timing.durationMs(state, i) > 0,
                        "non-positive duration for " + state + " frame " + i);
            }
        }
    }

    @Test
    void should_runningFasterThanIdle_when_comparingMiddleFrames() {
        PetTiming timing = PetTiming.defaults();
        assertTrue(timing.durationMs(PetState.RUNNING, 0)
                        < timing.durationMs(PetState.IDLE, 0),
                "running should be snappier than idle's long hold");
    }

    @Test
    void should_finalFrameLongerThanMidFrames_when_runningRight() {
        PetTiming timing = PetTiming.defaults();
        int count = timing.entryCount(PetState.RUNNING_RIGHT);
        int mid = timing.durationMs(PetState.RUNNING_RIGHT, 0);
        int last = timing.durationMs(PetState.RUNNING_RIGHT, count - 1);
        assertTrue(last > mid, "final running frame should be a longer hold");
    }
}
