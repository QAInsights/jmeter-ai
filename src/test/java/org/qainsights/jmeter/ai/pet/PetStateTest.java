package org.qainsights.jmeter.ai.pet;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetState}.
 */
class PetStateTest {

    @Test
    void should_haveNineStates_when_matchingAtlasRowCount() {
        assertEquals(PetState.ROW_COUNT, PetState.values().length);
    }

    @Test
    void should_mapEachStateToUniqueRow_when_coveringAllAtlasRows() {
        Set<Integer> rows = new HashSet<>();
        for (PetState state : PetState.values()) {
            assertTrue(rows.add(state.row()), "duplicate row " + state.row());
            assertTrue(state.row() >= 0 && state.row() < PetState.ROW_COUNT);
        }
    }

    @Test
    void should_matchCodexAtlasRowOrder_when_inspectingRows() {
        assertEquals(0, PetState.IDLE.row());
        assertEquals(1, PetState.RUNNING_RIGHT.row());
        assertEquals(2, PetState.RUNNING_LEFT.row());
        assertEquals(3, PetState.WAVING.row());
        assertEquals(4, PetState.JUMPING.row());
        assertEquals(5, PetState.FAILED.row());
        assertEquals(6, PetState.WAITING.row());
        assertEquals(7, PetState.RUNNING.row());
        assertEquals(8, PetState.REVIEW.row());
    }

    @Test
    void should_markOnlyOneShotStatesNonLooping_when_inspectingLoopFlags() {
        assertFalse(PetState.WAVING.isLooping());
        assertFalse(PetState.JUMPING.isLooping());
        assertFalse(PetState.FAILED.isLooping());
        assertTrue(PetState.IDLE.isLooping());
        assertTrue(PetState.RUNNING.isLooping());
        assertTrue(PetState.RUNNING_RIGHT.isLooping());
        assertTrue(PetState.RUNNING_LEFT.isLooping());
        assertTrue(PetState.WAITING.isLooping());
        assertTrue(PetState.REVIEW.isLooping());
    }
}
