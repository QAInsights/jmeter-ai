package org.qainsights.jmeter.ai.service;

import java.util.List;
import java.util.function.Consumer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.core.http.StreamResponse;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.qainsights.jmeter.ai.usage.AnthropicUsage;
import java.util.function.Consumer;

/**
 * ClaudeService class.
 */
public class ClaudeService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private final AnthropicClient client;
    private String systemPrompt;
    private boolean systemPromptInitialized = false;
    private long maxTokens;


    public ClaudeService() {
        // Default history size of 10, can be configured through jmeter.properties
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("claude.max.history.size", "10"));

        // Initialize the client
        String API_KEY = AiConfig.getProperty("anthropic.api.key", "YOUR_API_KEY");

        // Check if logging should be enabled
        String loggingLevel = AiConfig.getProperty("anthropic.log.level", "");
        if (!loggingLevel.isEmpty()) {
            // Set the environment variable for the Anthropic client logging
            System.setProperty("ANTHROPIC_LOG", loggingLevel);
            log.info("Enabled Anthropic client logging with level: {}", loggingLevel);
        }

        this.client = AnthropicOkHttpClient.builder()
                .apiKey(API_KEY)
                .build();

        // Get default model from properties or use SONNET if not specified
        this.currentModelId = AiConfig.getProperty("claude.default.model", "claude-sonnet-4-6");
        this.temperature = Float.parseFloat(AiConfig.getProperty("claude.temperature", "0.5"));
        this.maxTokens = Long.parseLong(AiConfig.getProperty("claude.max.tokens", "1024"));

        // Load system prompt from properties or use default
        try {
            systemPrompt = AiConfig.getProperty("claude.system.prompt", Constants.DEFAULT_JMETER_SYSTEM_PROMPT);

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

    public AnthropicClient getClient() {
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

    public long getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
        log.info("Max tokens set to: {}", maxTokens);
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
        log.info("Sending message to Claude: {}", message);
        return generateResponse(java.util.Collections.singletonList(message));
    }

    public String generateResponse(List<String> conversation) {
        try {
            log.info("Generating response for conversation with {} messages", conversation.size());

            // Ensure a model is set
            if (currentModelId == null || currentModelId.isEmpty()) {
                currentModelId = "claude-3-sonnet-20240229";
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

            // Build the request parameters
            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .maxTokens(maxTokens)
                    .temperature(temperature)
                    .model(currentModelId);

            // Only include the system prompt for the first message in a conversation
            if (isFirstMessage) {
                paramsBuilder.system(systemPrompt);
                log.info("Including system prompt in request (length: {})", systemPrompt.length());
            } else {
                log.info("Skipping system prompt to save tokens (already sent in previous messages)");
            }

            // Add messages from the conversation history
            for (int i = 0; i < limitedConversation.size(); i++) {
                String msg = limitedConversation.get(i);
                if (i % 2 == 0) {
                    // User messages
                    paramsBuilder.addUserMessage(msg);
                } else {
                    // Assistant (Claude) messages
                    paramsBuilder.addAssistantMessage(msg);
                }
            }

            MessageCreateParams params = paramsBuilder.build();
            log.info("Request parameters: maxTokens={}, temperature={}, model={}, messagesCount={}",
                    params.maxTokens(), params.temperature(), params.model(),
                    limitedConversation.size());

            Message message = client.messages().create(params);

            log.info(message.content().toString());

            // Estimate token usage (Anthropic doesn't provide exact usage in the response)
            // We can estimate based on characters - this is a rough estimate
            long estimatedPromptTokens = 0;
            for (String msg : limitedConversation) {
                estimatedPromptTokens += estimateTokens(msg);
            }

            if (isFirstMessage && systemPrompt != null && !systemPrompt.isEmpty()) {
                estimatedPromptTokens += estimateTokens(systemPrompt);
            }

            // Estimate response tokens
            String responseText = String.valueOf(message.content().get(0).text().get().text());
            long estimatedCompletionTokens = estimateTokens(responseText);

            // Record the estimated usage
            try {
                AnthropicUsage.getInstance().recordUsage(
                        message,
                        currentModelId,
                        estimatedPromptTokens,
                        estimatedCompletionTokens);
                log.info("Recorded estimated token usage: {} input, {} output",
                        estimatedPromptTokens, estimatedCompletionTokens);
            } catch (Exception e) {
                log.error("Failed to record token usage", e);
            }

            return responseText;
        } catch (Exception e) {
            log.error("Error generating response", e);

            // Extract and format error message for better readability
            String errorMessage = extractUserFriendlyErrorMessage(e);
            return "Error: " + errorMessage;
        }
    }

    /**
     * Estimates the number of tokens for a given text.
     * This is a rough estimate as Anthropic doesn't provide exact token counts in
     * the API response.
     * Uses a heuristic of characters/4 which works reasonably well in practice.
     * 
     * @param text The text to estimate tokens for
     * @return Estimated token count
     */
    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        log.info("Generating streaming response with specified model: {}", model);

        String modelToUse = (model != null && !model.isEmpty()) ? model : currentModelId;
        if (modelToUse == null || modelToUse.isEmpty()) {
            modelToUse = "claude-3-sonnet-20240229";
        }

        if (temperature < 0 || temperature > 1) {
            temperature = 0.7f;
        }

        boolean isFirstMessage = !systemPromptInitialized;
        if (isFirstMessage) {
            systemPromptInitialized = true;
        }

        List<String> limitedConversation = conversation;
        if (conversation.size() > maxHistorySize) {
            limitedConversation = conversation.subList(conversation.size() - maxHistorySize, conversation.size());
        }

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .maxTokens(maxTokens)
                .temperature(temperature)
                .model(modelToUse);

        if (isFirstMessage) {
            paramsBuilder.system(systemPrompt);
        }

        for (int i = 0; i < limitedConversation.size(); i++) {
            String msg = limitedConversation.get(i);
            if (i % 2 == 0) {
                paramsBuilder.addUserMessage(msg);
            } else {
                paramsBuilder.addAssistantMessage(msg);
            }
        }

        MessageCreateParams params = paramsBuilder.build();

        long estimatedPromptTokens = 0;
        for (String msg : limitedConversation) {
            estimatedPromptTokens += estimateTokens(msg);
        }
        if (isFirstMessage && systemPrompt != null && !systemPrompt.isEmpty()) {
            estimatedPromptTokens += estimateTokens(systemPrompt);
        }

        final long finalPromptTokens = estimatedPromptTokens;
        final String finalModel = modelToUse;

        Thread streamThread = new Thread(() -> {
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
                StringBuilder fullResponse = new StringBuilder();
                stream.stream()
                        .flatMap(event -> event.contentBlockDelta().stream())
                        .flatMap(delta -> delta.delta().text().stream())
                        .map(TextDelta::text)
                        .forEach(text -> {
                            fullResponse.append(text);
                            javax.swing.SwingUtilities.invokeLater(() -> tokenConsumer.accept(text));
                        });

                long estimatedCompletionTokens = estimateTokens(fullResponse.toString());
                try {
                    AnthropicUsage.getInstance().recordUsage(
                            null,
                            finalModel,
                            finalPromptTokens,
                            estimatedCompletionTokens);
                } catch (Exception e) {
                    log.error("Failed to record token usage", e);
                }

                javax.swing.SwingUtilities.invokeLater(onComplete);
            } catch (Exception e) {
                log.error("Error in streaming response", e);
                String errorMessage = extractUserFriendlyErrorMessage(e);
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(new Exception(errorMessage, e)));
            }
        });

        streamThread.setDaemon(true);
        streamThread.start();

        return () -> {
            log.info("Cancelling Claude stream");
            if (streamThread.isAlive()) {
                streamThread.interrupt();
            }
        };
    }
    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Rough estimation: average token is ~4 characters
        // This is a simplification but works reasonably well in practice
        return Math.max(1, text.length() / 4);
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
        if (errorMessage != null && errorMessage.contains("credit balance is too low")) {
            return "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.";
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
            // Extract the actual error message from the AnthropicError format
            if (errorMessage.contains("AnthropicError")) {
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
        return "An error occurred while communicating with the Anthropic API. Please try again later.";
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

    public String getName() {
        return "Anthropic Claude";
    }
}
