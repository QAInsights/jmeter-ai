package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider-neutral normalization of the chat panel's flat conversation history
 * into the strict user/assistant/user/... shape every provider expects as seed
 * history for an agent run. Each provider's {@code ChatModel} then maps the
 * normalized list onto its own message types.
 */
public final class ConversationSeed {

    private ConversationSeed() {
    }

    /**
     * Drops a trailing unpaired turn (so the seed always ends on an assistant turn)
     * and keeps only the most recent {@code maxTurnPairs} user/assistant pairs.
     *
     * @param priorConversationTurns earlier turns in user/assistant/user/... order; may be null
     * @param maxTurnPairs           maximum number of pairs to retain
     * @return an even-sized, alternating list starting with a user turn
     */
    public static List<String> normalize(List<String> priorConversationTurns, int maxTurnPairs) {
        if (priorConversationTurns == null || priorConversationTurns.isEmpty() || maxTurnPairs < 1) {
            return Collections.emptyList();
        }
        List<String> turns = new ArrayList<>(priorConversationTurns);
        if (turns.size() % 2 != 0) {
            turns.remove(turns.size() - 1);
        }
        int maxEntries = maxTurnPairs * 2;
        if (turns.size() > maxEntries) {
            turns = turns.subList(turns.size() - maxEntries, turns.size());
        }
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }
}
