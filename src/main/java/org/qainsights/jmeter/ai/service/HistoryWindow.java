package org.qainsights.jmeter.ai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Trims chat history for provider requests. Services assign roles by position
 * (even = user, odd = assistant), so the kept window must start on a user turn.
 */
public final class HistoryWindow {

    /** Stands in for a reply that failed, so the turns after it keep their roles. */
    public static final String FAILED_REPLY = "(No reply: that request failed.)";

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

    /**
     * Replaces stored {@code Error:} replies in assistant slots with {@link #FAILED_REPLY};
     * dropping them would shift every later turn into the wrong role.
     */
    public static List<String> maskFailedReplies(List<String> window) {
        List<String> masked = new ArrayList<>(window);
        for (int i = 1; i < masked.size(); i += 2) {
            String msg = masked.get(i);
            if (msg != null && msg.startsWith("Error:")) {
                masked.set(i, FAILED_REPLY);
            }
        }
        return masked;
    }
}
