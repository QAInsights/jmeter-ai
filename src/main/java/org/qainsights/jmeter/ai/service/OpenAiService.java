package org.qainsights.jmeter.ai.service;

import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.usage.OpenAiUsage;

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private final OpenAIClient client;
    private boolean systemPromptInitialized = false;

    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private String systemPrompt;
    private long maxTokens;

    public OpenAiService() {
        String API_KEY = AiConfig.getProperty("openai.api.key", "");
        String loggingLevel = AiConfig.getProperty("openai.log.level", "");
        if (!loggingLevel.isEmpty()) {
            // Set the environment variable for the OpenAI client logging
            System.setProperty("OPENAI_LOG", loggingLevel);
            log.info("Enabled OpenAI client logging with level: {}", loggingLevel);
        }
        this.client = new OpenAIOkHttpClient.Builder().apiKey(API_KEY).build();

        // Set the client in the OpenAiUsage singleton for token usage tracking
        try {
            OpenAiUsage.getInstance().setClient(this.client);
            log.info("Set OpenAI client in OpenAiUsage during initialization");
        } catch (Exception e) {
            log.error("Failed to set OpenAI client in OpenAiUsage", e);
        }

        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("openai.max.history.size", "10"));
        this.currentModelId = AiConfig.getProperty("openai.default.model", "gpt-4o");
        this.temperature = Float.parseFloat(AiConfig.getProperty("openai.temperature", "0.7"));
        this.systemPrompt = Constants.DEFAULT_JMETER_SYSTEM_PROMPT;
        this.maxTokens = Long.parseLong(AiConfig.getProperty("openai.max.tokens", "4096"));

        // Load system prompt from properties or use default
        try {
            systemPrompt = AiConfig.getProperty("openai.system.prompt", Constants.DEFAULT_JMETER_SYSTEM_PROMPT);

            if (systemPrompt == null) {
                log.warn("System prompt is null, using default");
                systemPrompt = Constants.DEFAULT_JMETER_SYSTEM_PROMPT;
            }

            log.info("Loaded system prompt from properties (length: {})", systemPrompt.length());
            // Only log the first 100 characters of the system prompt to avoid flooding the
            // logs
            log.info("System prompt (first 100 chars): {}",
                    systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
        } catch (Exception e) {
            log.error("Error loading system prompt, using default", e);
            systemPrompt = Constants.DEFAULT_JMETER_SYSTEM_PROMPT;
        }
    }

    public OpenAIClient getClient() {
        return client;
    }

    public void setModel(String modelId) {
        this.currentModelId = modelId;
        log.info("Model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return currentModelId;
    }

    public void setTemperature(float temperature) {
        if (temperature < 0 || temperature >= 1) {
            log.warn("Temperature must be between 0 and 1. Provided value: {}. Setting to default 0.7", temperature);
            this.temperature = 0.7f;
        } else {
            this.temperature = temperature;
            log.info("Temperature set to: {}", temperature);
        }
    }

    public float getTemperature() {
        return temperature;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
        log.info("Max tokens set to: {}", maxTokens);
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    /**
     * Resets the system prompt initialization flag.
     * This should be called when starting a new conversation.
     */
    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false;
        log.info("Reset system prompt initialization flag");
    }

    public String sendMessage(String message) {
        log.info("Sending message to OpenAI: {}", message);
        return generateResponse(java.util.Collections.singletonList(message));
    }

    public String generateResponse(List<String> conversation) {
        try {
            log.info("Generating response for conversation with {} messages", conversation.size());

            // Ensure a model is set
            if (currentModelId == null || currentModelId.isEmpty()) {
                currentModelId = "gpt-4o";
                log.warn("No model was set, defaulting to: {}", currentModelId);
            }

            // Ensure a temperature is set
            if (temperature < 0 || temperature > 1) {
                temperature = 0.7f;
                log.warn("Invalid temperature value ({}), defaulting to: {}", temperature, 0.7f);
            }

            // Log which model is being used for this conversation
            log.info("Generating response using model: {} and temperature: {}", currentModelId, temperature);

            // Check if this is the first message in a conversation based on
            // systemPromptInitialized flag
            boolean isFirstMessage = !systemPromptInitialized;
            if (isFirstMessage) {
                log.info("Using system prompt (first 100 chars): {}",
                        systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
                systemPromptInitialized = true;
            } else {
                log.info("Using previously initialized conversation with system prompt");
            }

            // Limit conversation history to last 10 messages to avoid token limits
            List<String> limitedConversation = conversation;
            if (conversation.size() > maxHistorySize) {
                limitedConversation = conversation.subList(conversation.size() - maxHistorySize, conversation.size());
                log.info("Limiting conversation to last {} messages", limitedConversation.size());
            }

            // Some newer OpenAI models (o1, o3, gpt-5, etc.) only support temperature=1.
            // Detect these by model ID prefix and skip setting temperature for them.
            boolean supportsCustomTemperature = !currentModelId.startsWith("o1")
                    && !currentModelId.startsWith("o3")
                    && !currentModelId.startsWith("o4")
                    && !currentModelId.contains("-chat-latest");

            // Create a fresh builder for parameters following the working example
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .maxCompletionTokens(maxTokens)
                    .model(currentModelId);

            if (supportsCustomTemperature) {
                paramsBuilder.temperature(temperature);
            } else {
                log.info("Skipping temperature setting for model {} (uses default only)", currentModelId);
            }

            // Always include the system prompt
            paramsBuilder.addSystemMessage(systemPrompt);
            log.info("Including system prompt in request (length: {})", systemPrompt.length());

            // Debug log the conversation array
            log.info("Conversation size: {}", conversation.size());

            // Limit conversation history to last maxHistorySize messages to avoid token
            // limits
            List<String> limitedHistory;
            if (conversation.size() > maxHistorySize) {
                limitedHistory = conversation.subList(conversation.size() - maxHistorySize, conversation.size());
                log.info("Limiting conversation to last {} messages", limitedHistory.size());
            } else {
                limitedHistory = new java.util.ArrayList<>(conversation);
            }

            // Log the conversation for debugging
            for (int i = 0; i < limitedHistory.size(); i++) {
                log.info("Message[{}]: {}", i, limitedHistory.get(i));
            }

            // Filter out error responses that were stored in history to avoid poisoning requests
            List<String> cleanHistory = new java.util.ArrayList<>();
            for (String msg : limitedHistory) {
                if (msg != null && !msg.startsWith("Error:")) {
                    cleanHistory.add(msg);
                } else if (msg != null) {
                    log.warn("Skipping error message from conversation history: {}", msg.substring(0, Math.min(80, msg.length())));
                }
            }

            if (cleanHistory.isEmpty()) {
                log.warn("Conversation is empty after filtering, using default message");
                paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
            } else {
                // Process the conversation history
                // We'll assume the conversation alternates between user and assistant messages
                // with the first message being from the user
                for (int i = 0; i < cleanHistory.size(); i++) {
                    String msg = cleanHistory.get(i);
                    if (msg == null || msg.isEmpty()) {
                        log.warn("Skipping empty message at position {}", i);
                        continue;
                    }

                    if (i % 2 == 0) {
                        // User messages (even indices: 0, 2, 4...)
                        paramsBuilder.addUserMessage(msg);
                        log.info("Added user message {}: {}", i,
                                msg.substring(0, Math.min(50, msg.length())));
                    } else {
                        // Assistant messages (odd indices: 1, 3, 5...)
                        // For OpenAI Java SDK 0.31.0, we need to use a different approach
                        // Since we can't directly add assistant messages, we'll add them as system
                        // messages
                        // This is a workaround and not ideal, but it should work
                        paramsBuilder.addSystemMessage("Assistant: " + msg);
                        log.info("Added assistant message as system message {}: {}", i,
                                msg.substring(0, Math.min(50, msg.length())));
                    }
                }
            }

            // Build the parameters and create the chat completion
            ChatCompletionCreateParams params = paramsBuilder.build();
            log.info("Request parameters: maxTokens={}, temperature={}, model={}, messagesCount={}",
                    params.maxCompletionTokens(), params.temperature(), params.model(),
                    conversation.size());

            // Debug log the messages in the request
            log.info("Request messages: {}", params.messages());

            ChatCompletion chatCompletion = client.chat().completions().create(params);

            log.info("Chat completions {}", chatCompletion);

            // Record usage data if available
            try {
                OpenAiUsage.getInstance().recordUsage(chatCompletion, currentModelId);
                log.info("Recorded token usage for model: {}", currentModelId);
            } catch (Exception ex) {
                log.error("Failed to record token usage", ex);
            }

            // Extract the response content using SDK methods
            String responseContent;
            try {
                // Get the first choice
                ChatCompletion.Choice choice = chatCompletion.choices().get(0);

                // Extract the content from the message
                // The SDK provides methods to access the message and its content
                responseContent = choice.message().content().orElse("No content available");
            } catch (Exception ex) {
                log.error("Error extracting content using SDK methods", ex);

                // Fallback to using toString() if SDK methods fail
                String choiceStr = chatCompletion.choices().get(0).toString();

                // Extract just the actual content text
                int contentStart = choiceStr.indexOf("content=");
                if (contentStart > 0) {
                    contentStart += 8; // Move past "content="

                    // Find the end of the content (before refusal or annotations)
                    int contentEnd = choiceStr.indexOf(", refusal=", contentStart);
                    if (contentEnd < 0) {
                        contentEnd = choiceStr.indexOf(", annotations=", contentStart);
                    }
                    if (contentEnd < 0) {
                        contentEnd = choiceStr.indexOf("}", contentStart);
                    }

                    if (contentEnd > contentStart) {
                        responseContent = choiceStr.substring(contentStart, contentEnd);
                    } else {
                        responseContent = choiceStr.substring(contentStart);
                    }
                } else {
                    responseContent = choiceStr;
                }
            }

            return responseContent;
        } catch (Exception e) {
            log.error("Error generating response", e);

            // Extract and format error message for better readability
            String errorMessage = extractUserFriendlyErrorMessage(e);
            return "Error: " + errorMessage;
        }
    }

    /**
     * Generates a response from the AI using the specified model.
     * 
     * @param conversation The conversation history
     * @param model        The specific model to use for this request
     * @return The AI's response
     */
    public String generateResponse(List<String> conversation, String model) {
        log.info("Generating response with specified model: {}", model);

        // Store current model
        String originalModel = this.currentModelId;

        try {
            // Set the specified model
            this.currentModelId = model;

            // Generate the response using the specified model
            return generateResponse(conversation);
        } finally {
            // Restore the original model
            this.currentModelId = originalModel;
            log.info("Restored original model: {}", originalModel);
        }
    }

    /**
     * Extracts a user-friendly error message from an exception
     * 
     * @param e The exception to extract the error message from
     * @return A user-friendly error message
     */
    private String extractUserFriendlyErrorMessage(Exception e) {
        String errorMessage = e.getMessage();

        // Check for credit balance error
        if (errorMessage != null && errorMessage.contains("insufficient_quota")) {
            return "Your credit balance is too low to access the OpenAI API. Please check your billing information.";
        }

        // Check for API key error
        if (errorMessage != null && errorMessage.contains("invalid_api_key")) {
            return "Invalid API key. Please check your API key and try again.";
        }

        // Check for rate limit error
        if (errorMessage != null && errorMessage.contains("rate_limit_exceeded")) {
            return "Rate limit exceeded. Please try again later.";
        }

        // Check for model not found error
        if (errorMessage != null && errorMessage.contains("model_not_found")) {
            return "The selected model was not found. Please select a different model.";
        }

        // Check for context length error
        if (errorMessage != null && errorMessage.contains("context_length_exceeded")) {
            return "The conversation is too long. Please start a new conversation.";
        }

        // For other errors, provide a cleaner message
        if (errorMessage != null) {
            // Try to extract a more readable message
            if (errorMessage.contains("OpenAIError")) {
                // Try to extract the message field from the error JSON
                int messageStart = errorMessage.indexOf("message=");
                if (messageStart != -1) {
                    int messageEnd = errorMessage.indexOf("}", messageStart);
                    if (messageEnd != -1) {
                        return errorMessage.substring(messageStart + 8, messageEnd);
                    }
                }
            }
        }

        // If we couldn't extract a specific error message, return a generic one
        return "An error occurred while communicating with the OpenAI API. Please try again later.";
    }

    public String getName() {
        return "OpenAI";
    }
}
