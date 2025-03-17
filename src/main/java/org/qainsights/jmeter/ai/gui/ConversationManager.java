package org.qainsights.jmeter.ai.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler;
import org.qainsights.jmeter.ai.optimizer.OptimizeRequestHandler;
import com.anthropic.models.ModelInfo;

/**
 * Manages the conversation history and AI interactions.
 * This class is responsible for sending messages to the AI and processing responses.
 */
public class ConversationManager {
    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    
    private final List<String> conversationHistory;
    private final ClaudeService claudeService;
    private final MessageProcessor messageProcessor;
    private final JTextPane chatArea;
    private final JTextArea messageField;
    private final JButton sendButton;
    private final JComboBox<ModelInfo> modelSelector;
    private final ElementSuggestionManager elementSuggestionManager;
    
    /**
     * Constructs a new ConversationManager.
     * 
     * @param chatArea The chat area to display messages
     * @param messageField The message field for user input
     * @param sendButton The send button
     * @param modelSelector The model selector
     * @param claudeService The Claude service for AI interactions
     * @param messageProcessor The message processor for formatting
     * @param elementSuggestionManager The element suggestion manager
     */
    public ConversationManager(JTextPane chatArea, JTextArea messageField, JButton sendButton,
                              JComboBox<ModelInfo> modelSelector, ClaudeService claudeService,
                              MessageProcessor messageProcessor, ElementSuggestionManager elementSuggestionManager) {
        this.chatArea = chatArea;
        this.messageField = messageField;
        this.sendButton = sendButton;
        this.modelSelector = modelSelector;
        this.claudeService = claudeService;
        this.messageProcessor = messageProcessor;
        this.elementSuggestionManager = elementSuggestionManager;
        this.conversationHistory = new ArrayList<>();
    }
    
    /**
     * Starts a new conversation by clearing the chat area and conversation history.
     */
    public void startNewConversation() {
        log.info("Starting new conversation");
        
        // Clear the chat area
        chatArea.setText("");
        
        // Clear the conversation history
        conversationHistory.clear();
        
        // Display welcome message
        displayWelcomeMessage();
    }
    
    /**
     * Displays a welcome message in the chat area.
     */
    public void displayWelcomeMessage() {
        log.info("Displaying welcome message");
        
        String welcomeMessage = "# Welcome to Feather Wand - JMeter Agent\n\n" +
                "I'm here to help you with your JMeter test plan. You can ask me questions about JMeter, " +
                "request help with creating test elements, or get advice on optimizing your tests.\n\n" +
                "**Special commands:**\n" +
                "- Use `@this` to get information about the currently selected element\n" +
                "- Use `@optimize` to get optimization suggestions for your test plan\n\n" +
                "How can I assist you today?";
        
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, new Color(0, 51, 102), true);
        } catch (BadLocationException e) {
            log.error("Error displaying welcome message", e);
        }
    }
    
    /**
     * Sends a user message to the AI and processes the response.
     * 
     * @param message The message to send
     */
    public void sendUserMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        
        log.info("Sending user message: {}", message);
        
        // Add the user message to the chat
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "You: " + message, Color.BLACK,false);
        } catch (BadLocationException e) {
            log.error("Error appending user message to chat", e);
        }
        
        // Add the user message to the conversation history
        conversationHistory.add(message);
        
        // Add "AI is thinking..." indicator
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "AI is thinking...", Color.GRAY, false);
        } catch (BadLocationException e) {
            log.error("Error adding loading indicator", e);
        }
        
        // Check for special commands
        if (message.trim().startsWith("@this")) {
            handleThisCommand();
            return;
        } else if (message.trim().startsWith("@optimize")) {
            handleOptimizeCommand();
            return;
        }
        
        log.info("Checking if message is an element request: '{}'", message);
        
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);
        
        String elementResponse = JMeterElementRequestHandler.processElementRequest(message);
        
        // Only process as an element request if it's a valid request
        // This prevents general conversation from being interpreted as element requests
        if (elementResponse != null && !elementResponse.contains("I couldn't understand what to do with")) {
            log.info("Detected element request, response: '{}'",
                    elementResponse.length() > 50 ? elementResponse.substring(0, 50) + "..." : elementResponse);
            
            // Disable input while processing
            messageField.setEnabled(false);
            sendButton.setEnabled(false);
            
            // Remove the loading indicator since we're about to display the response
            removeLoadingIndicator();
            processAiResponse(elementResponse);
            
            // Re-enable input after processing
            messageField.setEnabled(true);
            sendButton.setEnabled(true);
            messageField.requestFocusInWindow();
            
            return;
        }
        
        log.info("Message not recognized as an element request, processing as regular AI request");
        
        // Process the message in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return getAiResponse(message);
            }
            
            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Get the AI response
                    String response = get();
                    
                    // Process the AI response
                    processAiResponse(response);
                    
                    // Add the AI response to the conversation history
                    conversationHistory.add(response);
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting AI response", e);
                    
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while processing your request. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }
    
    /**
     * Handles the @this command to get information about the currently selected element.
     */
    private void handleThisCommand() {
        log.info("Processing @this command");
        
        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);
        
        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String elementInfo = getCurrentElementInfo();
                if (elementInfo == null) {
                    return "No element is currently selected in the test plan. Please select an element and try again.";
                }
                return elementInfo;
            }
            
            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Get the element info
                    String info = get();
                    
                    // Process the response
                    processAiResponse(info);
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting element info", e);
                    
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while getting element information. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }
    
    /**
     * Handles the @optimize command to get optimization suggestions for the test plan.
     */
    private void handleOptimizeCommand() {
        log.info("Processing @optimize command");
        
        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);
        
        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get optimization suggestions from OptimizeRequestHandler
                return OptimizeRequestHandler.analyzeAndOptimizeSelectedElement();
            }
            
            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Get the optimization suggestions
                    String suggestions = get();
                    
                    // Process the response
                    processAiResponse(suggestions);
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting optimization suggestions", e);
                    
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while getting optimization suggestions. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }
    
    /**
     * Gets information about the currently selected element.
     * 
     * @return Information about the currently selected element, or null if no element is selected
     */
    private String getCurrentElementInfo() {
        // This method would be implemented to get information about the currently selected element
        // For now, we'll return a placeholder message
        return "Information about the currently selected element would be displayed here.";
    }
    
    /**
     * Removes the loading indicator from the chat area.
     */
    private void removeLoadingIndicator() {
        log.info("Attempting to remove loading indicator");
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // Find the loading indicator text
            String text = doc.getText(0, doc.getLength());
            int index = text.lastIndexOf("AI is thinking...");
            
            log.info("Loading indicator found at index: {}", index);
            
            if (index != -1) {
                // Remove the loading indicator
                doc.remove(index, "AI is thinking...".length());
                log.info("Loading indicator removed");
            } else {
                log.warn("Loading indicator not found in chat text");
            }
        } catch (BadLocationException e) {
            log.error("Error removing loading indicator", e);
        }
    }
    
    /**
     * Processes an AI response and displays it in the chat area.
     * 
     * @param response The AI response to process
     */
    private void processAiResponse(String response) {
        if (response == null || response.isEmpty()) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "No response from AI. Please try again.", Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
            log.warn("Empty AI response");
            return;
        }
        
        log.info("Processing AI response: {}", response.substring(0, Math.min(100, response.length())));
        
        // Add the AI response to the chat
        log.info("Appending AI response to chat");
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), response, new Color(0, 51, 102), true);
        } catch (BadLocationException e) {
            log.error("Error appending AI response to chat", e);
        }
        
        // Create element buttons for context-aware suggestions after the AI response
        SwingUtilities.invokeLater(() -> {
            log.info("Creating element buttons for context-aware suggestions");
            elementSuggestionManager.createElementButtons(response);
        });
    }
    
    /**
     * Gets an AI response for a message.
     * 
     * @param message The message to get a response for
     * @return The AI response
     */
    private String getAiResponse(String message) {
        log.info("Getting AI response for message: {}", message);
        
        // Get the currently selected model from the dropdown
        ModelInfo selectedModel = (ModelInfo) modelSelector.getSelectedItem();
        if (selectedModel != null) {
            // Set the current model ID before generating the response
            log.info("Using model from dropdown for message: {}", selectedModel.id());
            claudeService.setModel(selectedModel.id());
        } else {
            log.warn("No model selected in dropdown, using default model: {}", claudeService.getCurrentModel());
        }
        
        // Call Claude API with full conversation history
        return claudeService.generateResponse(new ArrayList<>(conversationHistory));
    }
    
    /**
     * Gets the conversation history.
     * 
     * @return The conversation history
     */
    public List<String> getConversationHistory() {
        return conversationHistory;
    }
}
