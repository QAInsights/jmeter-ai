package org.qainsights.jmeter.ai.agent.loop;

import java.util.List;

/**
 * Provider-neutral, stateful conversation seam used by {@link AgentLoop}.
 * <p>
 * Implementations own the underlying message history and tool/system wiring for
 * a single agent run: {@link #start(String)} begins the conversation with the
 * user's request, and {@link #next(List)} feeds the results of the previous
 * turn's tool calls back to the model. Both return the model's next
 * {@link AssistantTurn}. A fresh instance is expected per run.
 */
public interface ChatModel {

    /** Sends the initial user message and returns the model's first turn. */
    AssistantTurn start(String userMessage);

    /** Sends the outcomes of the previous turn's tool calls and returns the next turn. */
    AssistantTurn next(List<ToolOutcome> toolOutcomes);

    /**
     * Returns and clears the reasoning text captured from the most recent turn's
     * response (e.g. a Claude thinking block), or null when there was none. The
     * {@link AgentLoop} drains this after every turn and forwards it to the run's
     * reasoning consumer, so extended thinking renders in the UI instead of being
     * silently dropped by the tool adapters. Default: no reasoning.
     */
    default String consumeLastReasoning() {
        return null;
    }
}
