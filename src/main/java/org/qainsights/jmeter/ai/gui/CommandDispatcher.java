package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.lint.LintCommandHandler;
import org.qainsights.jmeter.ai.optimizer.OptimizeRequestHandler;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.usage.UsageCommandHandler;
import org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler;
import org.qainsights.jmeter.ai.wrap.WrapCommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
