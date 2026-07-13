package org.qainsights.jmeter.ai.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider-neutral reason&harr;act loop. Drives a {@link ChatModel}: each turn,
 * any requested tool calls are executed via {@link ToolExecutor} and their
 * outcomes fed back, until the model returns a turn with no tool calls or the
 * iteration cap is hit. Tool calls within a turn run sequentially.
 * <p>
 * The loop is intentionally free of any LLM SDK types, so it is fully
 * unit-testable with a scripted {@link ChatModel}.
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** Outcome of a full run. */
    public static final class AgentResult {
        private final boolean completed;
        private final String finalText;
        private final int iterations;

        private AgentResult(boolean completed, String finalText, int iterations) {
            this.completed = completed;
            this.finalText = finalText == null ? "" : finalText;
            this.iterations = iterations;
        }

        static AgentResult completed(String text, int iterations) {
            return new AgentResult(true, text, iterations);
        }

        static AgentResult exhausted(String text, int iterations) {
            return new AgentResult(false, text, iterations);
        }

        /** True if the model produced a final answer; false if the iteration cap was hit. */
        public boolean isCompleted() {
            return completed;
        }

        public String getFinalText() {
            return finalText;
        }

        public int getIterations() {
            return iterations;
        }
    }

    private final ChatModel model;
    private final ToolExecutor executor;
    private final int maxIterations;

    public AgentLoop(ChatModel model, ToolExecutor executor, int maxIterations) {
        this.model = model;
        this.executor = executor;
        this.maxIterations = maxIterations < 1 ? 1 : maxIterations;
    }

    /**
     * Runs the loop for one user message.
     *
     * @param userMessage the user's request
     * @param progress    receives human-readable progress lines (tool calls/results); may be null
     * @return the run outcome
     */
    public AgentResult run(String userMessage, Consumer<String> progress) {
        return run(userMessage, progress, null);
    }

    /**
     * Runs the loop for one user message, additionally notifying {@code onToolCallStarted}
     * with the raw {@link AssistantTurn.ToolCall} just before each call is executed - e.g.
     * to drive a UI highlight of whatever element a tool call targets. Unlike {@code progress}
     * (a formatted display string), this exposes the call's structured arguments.
     *
     * @param userMessage        the user's request
     * @param progress           receives human-readable progress lines (tool calls/results); may be null
     * @param onToolCallStarted  notified with each tool call about to run, before it executes; may be null
     * @return the run outcome
     */
    public AgentResult run(String userMessage, Consumer<String> progress,
                            Consumer<AssistantTurn.ToolCall> onToolCallStarted) {
        Consumer<String> sink = progress == null ? s -> { } : progress;
        Consumer<AssistantTurn.ToolCall> toolSink = onToolCallStarted == null ? c -> { } : onToolCallStarted;

        AssistantTurn turn = model.start(userMessage);
        int iterations = 1;

        while (true) {
            if (!turn.hasToolCalls()) {
                return AgentResult.completed(turn.getText(), iterations);
            }
            if (iterations >= maxIterations) {
                log.warn("Agent loop hit iteration cap ({})", maxIterations);
                return AgentResult.exhausted(turn.getText(), iterations);
            }

            List<ToolOutcome> outcomes = new ArrayList<>();
            for (AssistantTurn.ToolCall call : turn.getToolCalls()) {
                toolSink.accept(call);
                sink.accept("\u2192 " + call.getName() + " " + call.getArguments());
                ToolResult result = executor.execute(call.getName(), call.getArguments());
                ToolOutcome outcome = ToolOutcome.from(call, result);
                outcomes.add(outcome);
                sink.accept("\u2190 " + (outcome.isError() ? outcome.getContent()
                        : truncate(outcome.getContent())));
            }

            turn = model.next(outcomes);
            iterations++;
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "\u2026";
    }
}
