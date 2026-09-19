package org.qainsights.jmeter.ai.agent;

import java.util.function.Consumer;

import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;

/**
 * The optional per-run callbacks {@link JMeterAgent} reports to: tool-activity
 * progress lines, tool-call starts (for the tree activity cue), streamed reasoning,
 * Jev routing/expansion notices, and Jev failure-triage notices. Any slot may be
 * null; {@link #empty()} is the no-op bundle for callers that want none of them.
 */
public record AgentRunListeners(Consumer<String> progress,
                                Consumer<AssistantTurn.ToolCall> toolCallStarted,
                                Consumer<String> reasoning,
                                Consumer<AgentRequestRouter.Notice> routingNotice,
                                Consumer<TriageNotice> triageNotice) {

    /** A bundle with no consumers - all reporting is skipped. */
    public static AgentRunListeners empty() {
        return new AgentRunListeners(null, null, null, null, null);
    }

    /** Safe accessor: never null inside a run. */
    static AgentRunListeners orEmpty(AgentRunListeners listeners) {
        return listeners == null ? empty() : listeners;
    }
}
