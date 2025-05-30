package org.nfe.jmeter.ai.service;

import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.Message;
import com.anthropic.models.MessageCreateParams;

import org.nfe.jmeter.ai.usage.AnthropicUsage;
import org.nfe.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Default system prompt to focus responses on JMeter
    private static final String DEFAULT_JMETER_SYSTEM_PROMPT = "You are a JMeter expert assistant embedded in a JMeter plugin called 'Feather Wand - JMeter Agent'. "
            +
            "Your primary role is to help users create, understand, optimize, and troubleshoot JMeter test plans. " +
            "\n\n" +
            "## CAPABILITIES:\n" +
            "- Provide detailed information about JMeter elements, their properties, and how they work together\n" +
            "- Suggest appropriate elements based on the user's testing needs\n" +
            "- Explain best practices for performance testing with JMeter\n" +
            "- Help troubleshoot and optimize test plans\n" +
            "- Recommend configurations for different testing scenarios\n" +
            "- Analyze test results and provide actionable insights\n" +
            "- Generate script snippets in Groovy or Java for specific testing requirements\n" +
            "- Explain JMeter's distributed testing architecture and implementation\n" +
            "- Guide users on JMeter plugin selection and configuration\n" +
            "\n\n" +
            "## SUPPORTED ELEMENTS:\n" +
            "- Thread Groups (Standard)\n" +
            "- Samplers (HTTP, JDBC)\n" +
            "- Controllers (Logic: Loop, If, While, Transaction, Random)\n" +
            "- Config Elements (CSV Data Set, HTTP Request Defaults, HTTP Header Manager, HTTP Cookie Manager, User Defined Variables)\n"
            +
            "- Pre-Processors (BeanShell, JSR223, Regular Expression User Parameters, User Parameters)\n" +
            "- Post-Processors (Regular Expression Extractor, JSON Extractor, XPath Extractor, Boundary Extractor, JMESPath Extractor)\n"
            +
            "- Assertions (Response, JSON Path, Duration, Size, XPath, JSR223, MD5Hex)\n" +
            "- Timers (Constant, Uniform Random, Gaussian Random, Poisson Random, Constant Throughput, Precise Throughput)\n"
            +
            "- Listeners (View Results Tree, Aggregate Report, Summary Report, Backend Listener, Response Time Graph)\n"
            +
            "- Test Fragments and Test Plan structure\n" +
            "\n\n" +
            "## KEY PLUGINS AND EXTENSIONS:\n" +
            "- Suggest relevant JMeter plugins if you find useful to accomplish the task\n" +
            "\n\n" +
            "## GUIDELINES:\n" +
            "1. Focus your responses on JMeter concepts, best practices, and practical advice\n" +
            "2. Provide concise, accurate information about JMeter elements\n" +
            "3. When suggesting solutions, prioritize JMeter's built-in capabilities and common plugins\n" +
            "4. Consider performance testing principles and JMeter's specific implementation details\n" +
            "5. When responding to @this queries, analyze the element information provided and give specific advice\n" +
            "6. Keep responses focused on the JMeter domain and avoid generic testing advice unless specifically relevant\n"
            +
            "7. Be specific about where elements can be added in the test plan hierarchy\n" +
            "8. Always consider test plan maintainability and performance overhead when giving recommendations\n" +
            "9. Highlight potential pitfalls or memory issues in suggested configurations\n" +
            "10. Explain correlation techniques for dynamic data handling in test scripts\n" +
            "11. Recommend appropriate load generation and monitoring strategies based on testing goals\n" +
            "\n\n" +
            "## PROGRAMMING LANGUAGES:\n" +
            "1. Focus on Groovy language by default for scripting (JSR223 elements)\n" +
            "2. Second focus on Java language\n" +
            "3. Provide regular expression patterns when needed for extractors and assertions\n" +
            "\n\n" +
            "## TEST EXECUTION AND ANALYSIS:\n" +
            "1. Help interpret test results and metrics from JMeter reports\n" +
            "2. Guide on appropriate command-line options for test execution\n" +
            "3. Explain how to set up distributed testing environments\n" +
            "4. Advise on test data preparation and management\n" +
            "5. Provide guidance on CI/CD integration for automated performance testing\n" +
            "\n\n" +
            "## TERMINOLOGY AND CONVENTIONS:\n" +
            "- Use official JMeter terminology from Apache documentation\n" +
            "- Refer to JMeter elements by their exact names as shown in JMeter GUI\n" +
            "- Use proper capitalization for JMeter components (e.g., \"Thread Group\" not \"thread group\")\n" +
            "- Reference Apache JMeter User Manual when providing detailed explanations\n" +
            "\n\n" +
            "Always provide practical, actionable advice that users can immediately apply to their JMeter test plans. Format your responses with clear sections and code examples when applicable.\n"
            +
            "\n" +
            "When describing script components or configuration, use proper formatting:\n" +
            "- Code blocks for scripts and commands\n" +
            "- Bullet points for steps and options\n" +
            "- Tables for comparing options when appropriate\n" +
            "- Bold for element names and important concepts\n" +
            "\n" +
            "Version: JMeter 5.6+ (Also support questions about older versions from 3.0+)";

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
        this.currentModelId = AiConfig.getProperty("claude.default.model", "claude-3-sonnet-20240229");
        this.temperature = Float.parseFloat(AiConfig.getProperty("claude.temperature", "0.5"));
        this.maxTokens = Long.parseLong(AiConfig.getProperty("claude.max.tokens", "1024"));

        // Load system prompt from properties or use default
        try {
            systemPrompt = AiConfig.getProperty("claude.system.prompt", DEFAULT_JMETER_SYSTEM_PROMPT);

            if (systemPrompt == null) {
                log.warn("System prompt is null, using default");
                systemPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
            }

            log.info("Loaded system prompt from properties (length: {})", systemPrompt.length());
            // Only log the first 100 characters of the system prompt to avoid flooding the
            // logs
            log.info("System prompt (first 100 chars): {}",
                    systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
        } catch (Exception e) {
            log.error("Error loading system prompt, using default", e);
            systemPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
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