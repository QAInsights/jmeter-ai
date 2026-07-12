package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.lint.LintCommandHandler;
import org.qainsights.jmeter.ai.optimizer.OptimizeRequestHandler;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.usage.UsageCommandHandler;
import org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.wrap.WrapCommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingWorker;

/**
 * Handles dispatching of user messages and special @ commands to the
 * appropriate command handlers, running background work via SwingWorker.
 */
public class CommandDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final CommandCallback cb;

    public CommandDispatcher(CommandCallback callback) {
        this.cb = callback;
    }

    /**
     * Entry point: processes a raw user message, dispatches special commands or
     * falls back to a general AI request.
     *
     * @param message the trimmed user message
     */
    public void dispatch(String message) {
        if (message.isEmpty()) {
            return;
        }

        log.info("Sending user message: {}", message);
        cb.appendUserMessage("You: " + message);
        cb.addToConversationHistory(message);
        cb.clearMessageField();
        cb.appendLoadingIndicator();

        switch (getCommand(message)) {
            case "@this":
                handleThisCommand();
                return;
            case "@optimize":
                handleOptimizeCommand();
                return;
            case "@code":
                cb.appendRedMessage(
                        "The @code command is disabled. Please use the right-click context menu in the JSR223 editor instead.");
                cb.setInputEnabled(true);
                return;
            case "@lint":
                handleLintCommand(message);
                return;
            case "@wrap":
                handleWrapCommand();
                return;
            case "@usage":
                handleUsageCommand();
                return;
            default:
                break;
        }

        // Tier 2: agentic tool-calling loop (feature-flagged; Claude only for the MVP).
        if (JMeterAgent.isEnabled() && isClaudeModel(cb.getSelectedModel())) {
            handleAgentCommand(message);
            return;
        }

        log.info("Checking if message is an element request: '{}'", message);
        cb.setInputEnabled(false);

        String elementResponse = JMeterElementRequestHandler.processElementRequest(message);
        if (elementResponse != null && !elementResponse.contains("I couldn't understand what to do with")) {
            log.info("Detected element request");
            cb.removeLoadingIndicator();
            cb.processAiResponse(elementResponse);
            cb.setInputEnabled(true);
            return;
        }

        if (AiConfig.isStreamingEnabled()) {
            log.info("Processing as streaming AI request");
            cb.showStopButton();

            StringBuilder fullResponse = new StringBuilder();

            Runnable cancelHandle = cb.getAiStreamResponse(message,
                token -> {
                    fullResponse.append(token);
                    cb.appendStreamToken(token);
                },
                () -> {
                    String response = fullResponse.toString();
                    cb.onStreamComplete(response);
                    cb.addToConversationHistory(response);
                },
                e -> {
                    cb.onStreamError("Error getting AI stream response", e,
                        "Sorry, I encountered an error while processing your request. Please try again.");
                }
            );
        } else {
            log.info("Processing as regular AI request");
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    return cb.getAiResponse(message);
                }

                @Override
                protected void done() {
                    try {
                        String response = get();
                        cb.onWorkerSuccess(response);
                        cb.addToConversationHistory(response);
                    } catch (InterruptedException | ExecutionException e) {
                        cb.onWorkerError("Error getting AI response", e,
                                "Sorry, I encountered an error while processing your request. Please try again.");
                    }
                }
            }.execute();
        }
    }

    private void handleThisCommand() {
        log.info("Processing @this command");
        cb.setLastCommandType("NONE");
        cb.setInputEnabled(false);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String elementInfo = cb.getCurrentElementInfo();
                return elementInfo != null ? elementInfo
                        : "No element is currently selected in the test plan. Please select an element and try again.";
            }

            @Override
            protected void done() {
                try {
                    cb.onWorkerSuccess(get());
                } catch (InterruptedException | ExecutionException e) {
                    cb.onWorkerError("Error getting element info", e,
                            "Sorry, I encountered an error while getting element information. Please try again.");
                }
            }
        }.execute();
    }

    private void handleOptimizeCommand() {
        log.info("Processing @optimize command");
        cb.setLastCommandType("NONE");
        cb.setInputEnabled(false);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String selectedModel = cb.getSelectedModel();
                AiService serviceToUse = cb.resolveAiService(selectedModel);
                return OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(serviceToUse);
            }

            @Override
            protected void done() {
                try {
                    cb.onWorkerSuccess(get());
                } catch (InterruptedException | ExecutionException e) {
                    cb.onWorkerError("Error getting optimization suggestions", e,
                            "Sorry, I encountered an error while getting optimization suggestions. Please try again.");
                }
            }
        }.execute();
    }

    private void handleLintCommand(String message) {
        log.info("Processing @lint command");
        cb.setLastCommandType("LINT");
        cb.setInputEnabled(false);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String selectedModel = cb.getSelectedModel();
                if (selectedModel == null) {
                    return "Please select a model first.";
                }
                AiService serviceToUse = cb.resolveAiService(selectedModel);
                LintCommandHandler lintCommandHandler = new LintCommandHandler(serviceToUse);
                return lintCommandHandler.processLintCommand(message);
            }

            @Override
            protected void done() {
                try {
                    cb.onWorkerSuccess(get());
                } catch (InterruptedException | ExecutionException e) {
                    cb.onWorkerError("Error processing lint command", e,
                            "Sorry, I encountered an error while processing your lint command. Please try again.");
                }
            }
        }.execute();
    }

    private void handleWrapCommand() {
        log.info("Processing @wrap command");
        cb.setLastCommandType("WRAP");
        cb.setInputEnabled(false);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return new WrapCommandHandler().processWrapCommand();
            }

            @Override
            protected void done() {
                try {
                    cb.onWorkerSuccess(get());
                } catch (InterruptedException | ExecutionException e) {
                    cb.onWorkerError("Error processing @wrap command", e,
                            "Sorry, I encountered an error while processing the @wrap command. Please try again.");
                }
            }
        }.execute();
    }

    private void handleUsageCommand() {
        log.info("Processing @usage command");
        cb.setInputEnabled(false);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String selectedModel = cb.getSelectedModel();
                AiService serviceToUse = selectedModel != null ? cb.resolveAiService(selectedModel) : null;
                return new UsageCommandHandler().processUsageCommand(serviceToUse);
            }

            @Override
            protected void done() {
                try {
                    cb.onWorkerSuccess(get());
                } catch (InterruptedException | ExecutionException e) {
                    cb.onWorkerError("Error processing usage command", e,
                            "Sorry, I encountered an error while processing the usage command. Please try again.");
                }
            }
        }.execute();
    }

    /** Delay between simulated stream tokens for the agent's final answer, in milliseconds. */
    private static final int FINAL_TEXT_TOKEN_DELAY_MS = 12;

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
     * Runs the agentic tool-calling loop for a message, streaming each tool
     * call/result line into the chat, then replaying the final summary
     * token-by-token (if {@code jmeter.ai.streaming.enabled}) via the same
     * simulated-streaming UI as the plain chat path. On any failure it degrades
     * to a plain (non-agentic) AI answer so the user is never left with a dead end.
     */
    private void handleAgentCommand(String message) {
        log.info("Processing message via agent loop");
        cb.setLastCommandType("NONE");
        cb.setInputEnabled(false);
        cb.removeLoadingIndicator();

        // The current message was just appended as the last entry; everything before
        // it is prior conversation context to seed the agent with multi-turn memory.
        List<String> history = cb.getConversationHistory();
        List<String> priorTurns = history.size() > 1
                ? new ArrayList<>(history.subList(0, history.size() - 1))
                : Collections.<String>emptyList();

        boolean streamFinalText = AiConfig.isStreamingEnabled();

        new SwingWorker<String, AgentChunk>() {
            @Override
            protected String doInBackground() {
                try {
                    AiService service = cb.resolveAiService(cb.getSelectedModel());
                    if (!(service instanceof ClaudeService)) {
                        return finish("Agent mode currently supports Claude models only. Select a Claude model and retry.");
                    }
                    JMeterAgent agent = JMeterAgent.forClaude((ClaudeService) service);
                    AgentLoop.AgentResult result = agent.run(message, priorTurns,
                            line -> publish(AgentChunk.progress(line)));
                    String summary = result.getFinalText();
                    if (!result.isCompleted()) {
                        summary = (summary.isEmpty() ? "" : summary + "\n\n")
                                + "[Agent stopped after reaching the step limit.]";
                    }
                    return finish(summary.isEmpty() ? "Done." : summary);
                } catch (RuntimeException agentError) {
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
                        cb.appendMessageToChat(chunk.getText());
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

    /** True when the selected model routes to Claude (the only agent provider in the MVP). */
    static boolean isClaudeModel(String selectedModel) {
        if (selectedModel == null || selectedModel.isEmpty()) {
            return true;
        }
        return !selectedModel.startsWith("openai:")
                && !selectedModel.startsWith("ollama:")
                && !selectedModel.startsWith("deepseek:")
                && !selectedModel.startsWith("google:");
    }

    /**
     * Extracts the leading @command token from a message.
     *
     * @param message the raw input message
     * @return the @command token, or "" if none
     */
    static String getCommand(String message) {
        String trimmed = message.trim();
        if (!trimmed.startsWith("@")) {
            return "";
        }
        int spaceIndex = trimmed.indexOf(' ');
        return spaceIndex == -1 ? trimmed : trimmed.substring(0, spaceIndex);
    }
}
