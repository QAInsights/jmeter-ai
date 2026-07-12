package org.qainsights.jmeter.ai.agent.correlation;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link CorrelationCandidateStore}. */
class CorrelationCandidateStoreTest {

    @AfterEach
    void tearDown() {
        CorrelationCandidateStore.clearForTests();
    }

    private static CorrelationCandidate candidate(String variableName) {
        CorrelationCandidate c = new CorrelationCandidate();
        c.setVariableName(variableName);
        return c;
    }

    @Test
    void get_initially_isEmpty() {
        assertTrue(CorrelationCandidateStore.get().isEmpty());
    }

    @Test
    void set_thenGet_returnsStoredCandidatesInOrder() {
        CorrelationCandidate a = candidate("a");
        CorrelationCandidate b = candidate("b");

        CorrelationCandidateStore.set(List.of(a, b));

        List<CorrelationCandidate> stored = CorrelationCandidateStore.get();
        assertEquals(2, stored.size());
        assertSame(a, stored.get(0));
        assertSame(b, stored.get(1));
    }

    @Test
    void set_null_storesEmptyList() {
        CorrelationCandidateStore.set(List.of(candidate("a")));
        CorrelationCandidateStore.set(null);

        assertTrue(CorrelationCandidateStore.get().isEmpty());
    }

    @Test
    void set_replacesPreviousContents() {
        CorrelationCandidateStore.set(List.of(candidate("a")));
        CorrelationCandidateStore.set(List.of(candidate("b")));

        List<CorrelationCandidate> stored = CorrelationCandidateStore.get();
        assertEquals(1, stored.size());
        assertEquals("b", stored.get(0).getVariableName());
    }

    @Test
    void get_returnsDefensiveCopy_mutatingReturnedListDoesNotAffectStore() {
        CorrelationCandidateStore.set(List.of(candidate("a")));

        List<CorrelationCandidate> first = CorrelationCandidateStore.get();
        first.clear();

        assertEquals(1, CorrelationCandidateStore.get().size());
    }

    @Test
    void clearForTests_emptiesStore() {
        CorrelationCandidateStore.set(List.of(candidate("a")));
        CorrelationCandidateStore.clearForTests();

        assertTrue(CorrelationCandidateStore.get().isEmpty());
    }

    @Test
    void set_emptyList_isAccepted() {
        CorrelationCandidateStore.set(Collections.emptyList());
        assertTrue(CorrelationCandidateStore.get().isEmpty());
    }
}
