package org.qainsights.jmeter.ai.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingWorker;

import org.qainsights.jmeter.ai.agent.AgentRunListeners;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.RateLimitErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the agentic tool-calling loop for a message on a SwingWorker, streaming
 * each tool call/result line into the chat, then replaying the final summary
 * token-by-token (if {@code jmeter.ai.streaming.enabled}) via the same
 * simulated-streaming UI as the plain chat path. On any failure it degrades to a
 * plain (non-agentic) AI answer so the user is never left with a dead end.
 * Extracted from {@link CommandDispatcher} to keep it under the file-size limit.
 */
final class AgentCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentCommandRunner.class);

    /** Delay between simulated stream tokens for the agent's final answer, in milliseconds. */
    private static final int FINAL_TEXT_TOKEN_DELAY_MS = 12;

    private final CommandCallback cb;
    private final TreeActivityGlowController glowController;

    AgentCommandRunner(CommandCallback cb, TreeActivityGlowController glowController) {
        this.cb = cb;
        this.glowController = glowController;
    }

    /** Tags a published chunk as either a tool progress line or a simulated final-text token. */
    private static final class AgentChunk {
        private final String text;
        private final boolean token;

        private AgentChunk(String text, boolean token) {
            this.text = text;
            this.token = token;
        }

        static AgentChunk progress(String text) {
            return new AgentChunk(text, false);
        }

        static AgentChunk token(String text) {
            return new AgentChunk(text, true);
        }

        String getText() {
            return text;
        }

        boolean isToken() {
            return token;
        }
    }

    /**
     * Extracts a tool call's {@code element_id} argument, if it has one, for
     * {@link TreeActivityGlowController#onToolCallStarted}. Tools with no such
     * argument (e.g. {@code run_test}, {@code save_plan}) pass {@code null}, which
     * the controller resolves to the Test Plan node as a generic activity cue.
     */
    private static String elementIdOf(AssistantTurn.ToolCall call) {
        Object id = call.getArguments().get("element_id");
        return id == null ? null : String.valueOf(id);
    }

    void run(String message) {
        log.info("Processing message via agent loop");
        cb.setLastCommandType("NONE");
        cb.setInputEnabled(false);
        cb.removeLoadingIndicator();

        // The current message was just appended as the last entry; everything before
        // it is prior conversation context to seed the agent with multi-turn memory.
        List<String> history = cb.getConversationHistory();
        List<String> priorTurns = history.size() > 1
                ? cb.resolveAttachmentMarkers(new ArrayList<>(history.subList(0, history.size() - 1)))
                : Collections.<String>emptyList();

        boolean streamFinalText = AiConfig.isStreamingEnabled();
        String selectedModel = cb.getSelectedModel();

        new SwingWorker<String, AgentChunk>() {
            @Override
            protected String doInBackground() {
                try {
                    AiService service = cb.resolveAiService(selectedModel);
                    JMeterAgent agent = JMeterAgent.forService(service);
                    if (agent == null) {
                        return finish("Agent mode currently supports Claude, OpenAI, Google Gemini, DeepSeek, Grok, "
                                + "Meta Muse, Codex and Claude Code models only. "
                                + "Select one of those and retry.");
                    }
                    AgentLoop.AgentResult result;
                    try {
                        String resolvedMessage = cb.resolveAttachmentMarkers(List.of(message)).get(0);
                        result = agent.run(resolvedMessage, message, priorTurns,
                                new AgentRunListeners(
                                        line -> publish(AgentChunk.progress(line)),
                                        call -> glowController.onToolCallStarted(elementIdOf(call)),
                                        reasoning -> cb.appendReasoningToken(reasoning),
                                        notice -> cb.appendJevRoute(notice, selectedModel),
                                        triage -> cb.appendJevTriage(triage, selectedModel)));
                    } finally {
                        glowController.onRunFinished();
                    }
                    String summary = result.getFinalText();
                    if (!result.isCompleted()) {
                        summary = (summary.isEmpty() ? "" : summary + "\n\n")
                                + "[Agent stopped after reaching the step limit.]";
                    }
                    return finish(summary.isEmpty() ? "Done." : summary);
                } catch (CliProviderException cliError) {
                    // The provider itself is unusable (CLI missing, not signed in,
                    // timed out): retrying as plain chat would just pay for the same
                    // failure twice, so surface the message the provider wrote.
                    log.warn("Agent run aborted: {}", cliError.getMessage());
                    return finish("Error: " + cliError.getMessage());
                } catch (RuntimeException agentError) {
                    if (RateLimitErrors.isRateLimited(agentError)) {
                        // Falling back to plain chat would spend more of the same exhausted quota.
                        log.warn("Agent run aborted by rate limit: {}", agentError.getMessage());
                        return finish("Error: " + RateLimitErrors.describe(agentError));
                    }
                    log.error("Agent loop failed, degrading to plain AI response", agentError);
                    publish(AgentChunk.progress("[Agent error: " + agentError.getMessage()
                            + " - falling back to a plain answer.]"));
                    return finish(cb.getAiResponse(message));
                }
            }

            /** Replays the final text token-by-token (if enabled) before returning it as-is. */
            private String finish(String finalText) {
                if (streamFinalText) {
                    for (String chunk : TextChunker.chunk(finalText)) {
                        publish(AgentChunk.token(chunk));
                        try {
                            Thread.sleep(FINAL_TEXT_TOKEN_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                return finalText;
            }

            @Override
            protected void process(List<AgentChunk> chunks) {
                for (AgentChunk chunk : chunks) {
                    if (chunk.isToken()) {
                        cb.appendStreamToken(chunk.getText());
                    } else {
                        cb.appendToolActivity(chunk.getText());
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    String response = get();
                    if (streamFinalText) {
                        cb.onStreamComplete(response);
                    } else {
                        cb.onWorkerSuccess(response);
                    }
                    cb.addToConversationHistory(response);
                } catch (InterruptedException | ExecutionException e) {
                    cb.onWorkerError("Error running the agent", e,
                            "Sorry, I encountered an error while running the agent. Please try again.");
                }
            }
        }.execute();
    }
}
