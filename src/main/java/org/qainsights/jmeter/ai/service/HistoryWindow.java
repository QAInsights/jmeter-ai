package org.qainsights.jmeter.ai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Trims chat history for provider requests. Services assign roles by position
 * (even = user, odd = assistant), so the kept window must start on a user turn.
 */
public final class HistoryWindow {

    private HistoryWindow() {
    }

    /** The last {@code max} entries at most, starting at a user turn. */
    public static List<String> lastTurns(List<String> conversation, int max) {
        int size = conversation.size();
        if (size <= max) {
            return new ArrayList<>(conversation);
        }
        if (max <= 0) {
            return new ArrayList<>();
        }
        int from = size - max;
        if (from % 2 == 1) {
            from++;
        }
        return new ArrayList<>(conversation.subList(from, size));
    }
}
