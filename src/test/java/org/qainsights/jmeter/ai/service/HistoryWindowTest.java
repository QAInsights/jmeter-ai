package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryWindowTest {

    private static List<String> turns(int count) {
        List<String> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add((i % 2 == 0 ? "user" : "assistant") + i);
        }
        return history;
    }

    @Test
    void shortHistoryIsKept() {
        assertEquals(turns(5), HistoryWindow.lastTurns(turns(5), 10));
    }

    @Test
    void oddHistoryWindowStartsOnAUserTurn() {
        List<String> window = HistoryWindow.lastTurns(turns(11), 10);

        assertEquals(9, window.size());
        assertEquals("user2", window.get(0));
        assertEquals("user10", window.get(8));
    }

    @Test
    void evenHistoryKeepsTheFullWindow() {
        List<String> window = HistoryWindow.lastTurns(turns(12), 10);

        assertEquals(10, window.size());
        assertEquals("user2", window.get(0));
        assertEquals("assistant11", window.get(9));
    }

    @Test
    void oddWindowSizeStillStartsOnAUserTurn() {
        List<String> window = HistoryWindow.lastTurns(turns(10), 5);

        assertEquals("user6", window.get(0));
        assertEquals(4, window.size());
    }

    @Test
    void windowOfOneKeepsTheLatestUserTurn() {
        assertEquals(List.of("user10"), HistoryWindow.lastTurns(turns(11), 1));
    }

    @Test
    void windowNeverExceedsTheLimit() {
        assertEquals(List.of(), HistoryWindow.lastTurns(turns(12), 1));
        assertEquals(List.of(), HistoryWindow.lastTurns(turns(12), 0));
    }

    @Test
    void failedRepliesKeepTheirAssistantSlot() {
        List<String> window = List.of("user0", "Error: timeout", "user2", "assistant3", "Error: odd slot");

        assertEquals(List.of("user0", HistoryWindow.FAILED_REPLY, "user2", "assistant3", "Error: odd slot"),
                HistoryWindow.maskFailedReplies(window));
    }
}
