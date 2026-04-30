package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.service.AiService;

import java.util.List;

/**
 * Callback interface implemented by AiChatPanel so that CommandDispatcher can
 * trigger UI updates and access shared state without holding a direct reference
 * to the panel itself.
 */
public interface CommandCallback {

    // --- UI state ---

    void setInputEnabled(boolean enabled);

    void clearMessageField();

    void appendUserMessage(String message);

    void appendLoadingIndicator();

    void removeLoadingIndicator();

    void processAiResponse(String response);

    void appendRedMessage(String message);

    // --- Streaming UI control ---

    void showStopButton();

    void hideStopButton();

    void appendStreamToken(String token);

    void onStreamComplete(String fullResponse);

    void onStreamError(String logMessage, Exception e, String userMessage);

    Runnable getAiStreamResponse(String message, java.util.function.Consumer<String> tokenConsumer, Runnable onComplete, java.util.function.Consumer<Exception> onError);

    // --- Shared data ---

    String getSelectedModel();

    List<String> getConversationHistory();

    void addToConversationHistory(String entry);

    // --- Service resolution ---

    String getAiResponse(String message);

    AiService resolveAiService(String selectedModel);

    String getCurrentElementInfo();

    // --- Command-type tracking ---

    void setLastCommandType(String type);

    // --- Chat display ---

    void appendMessageToChat(String message);

    void appendErrorMessageToChat(String context, Exception e);

    // --- Worker callbacks ---

    void onWorkerSuccess(String response);

    void onWorkerError(String logMessage, Exception e, String userMessage);
}
