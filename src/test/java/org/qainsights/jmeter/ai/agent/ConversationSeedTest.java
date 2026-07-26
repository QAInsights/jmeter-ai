package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ConversationSeed#normalize(List, int)}. */
class ConversationSeedTest {

    @Test
    void normalize_nullOrEmpty_returnsEmptyList() {
        assertTrue(ConversationSeed.normalize(null, 10).isEmpty());
        assertTrue(ConversationSeed.normalize(Collections.<String>emptyList(), 10).isEmpty());
    }

    @Test
    void normalize_evenTurns_keepsThemAsIs() {
        List<String> turns = Arrays.asList("ask", "answer");
        assertEquals(turns, ConversationSeed.normalize(turns, 10));
    }

    @Test
    void normalize_oddTurns_dropsTheTrailingUnpairedTurn() {
        List<String> turns = Arrays.asList("ask", "answer", "dangling ask");
        assertEquals(Arrays.asList("ask", "answer"), ConversationSeed.normalize(turns, 10));
    }

    @Test
    void normalize_singleTurn_dropsIt() {
        assertTrue(ConversationSeed.normalize(Collections.singletonList("ask"), 10).isEmpty());
    }

    @Test
    void normalize_moreThanMaxPairs_keepsTheMostRecentOnes() {
        List<String> turns = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            turns.add("ask " + i);
            turns.add("answer " + i);
        }

        List<String> normalized = ConversationSeed.normalize(turns, 2);

        assertEquals(Arrays.asList("ask 3", "answer 3", "ask 4", "answer 4"), normalized);
    }

    @Test
    void normalize_zeroOrNegativeMaxPairs_returnsEmptyList() {
        List<String> turns = Arrays.asList("ask", "answer");
        assertTrue(ConversationSeed.normalize(turns, 0).isEmpty());
        assertTrue(ConversationSeed.normalize(turns, -1).isEmpty());
    }

    @Test
    void normalize_doesNotMutateTheCallersList() {
        List<String> turns = new ArrayList<>(Arrays.asList("ask", "answer", "dangling"));

        ConversationSeed.normalize(turns, 10);

        assertEquals(3, turns.size());
    }

    @Test
    void normalize_returnsAnUnmodifiableList() {
        List<String> normalized = ConversationSeed.normalize(Arrays.asList("ask", "answer"), 10);
        assertThrows(UnsupportedOperationException.class, () -> normalized.add("nope"));
    }
}
