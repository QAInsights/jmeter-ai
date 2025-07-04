package org.qainsights.jmeter.ai.service;

import java.util.List;
import java.util.Collections;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.usage.BedrockUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * BedrockService class for AWS Bedrock integration with Claude models.
 */
public class BedrockService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(BedrockService.class);
    
    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private final BedrockRuntimeClient client;
    private String systemPrompt;
    private boolean systemPromptInitialized = false;
    private long maxTokens;
    private final ObjectMapper objectMapper;

    // Default system prompt to focus responses on JMeter
    private static final String DEFAULT_JMETER_SYSTEM_PROMPT = "You are a JMeter expert assistant embedded in a JMeter plugin called 'Feather Wand - JMeter Agent'. "
            + "Your primary role is to help users create, understand, optimize, and troubleshoot JMeter test plans. "
            + "\n\n"
            + "## CAPABILITIES:\n"
            + "- Provide detailed information about JMeter elements, their properties, and how they work together\n"
            + "- Suggest appropriate elements based on the user's testing needs\n"
            + "- Explain best practices for performance testing with JMeter\n"
            + "- Help troubleshoot and optimize test plans\n"
            + "- Recommend configurations for different testing scenarios\n"
            + "- Analyze test results and provide actionable insights\n"
            + "- Generate script snippets in Groovy or Java for specific testing requirements\n"
            + "- Explain JMeter's distributed testing architecture and implementation\n"
            + "- Guide users on JMeter plugin selection and configuration\n"
            + "\n\n"
            + "## SUPPORTED ELEMENTS:\n"
            + "- Thread Groups (Standard)\n"
            + "- Samplers (HTTP, JDBC)\n"
            + "- Controllers (Logic: Loop, If, While, Transaction, Random)\n"
            + "- Config Elements (CSV Data Set, HTTP Request Defaults, HTTP Header Manager, HTTP Cookie Manager, User Defined Variables)\n"
            + "- Pre-Processors (BeanShell, JSR223, Regular Expression User Parameters, User Parameters)\n"
            + "- Post-Processors (Regular Expression Extractor, JSON Extractor, XPath Extractor, Boundary Extractor, JMESPath Extractor)\n"
            + "- Assertions (Response, JSON Path, Duration, Size, XPath, JSR223, MD5Hex)\n"
            + "- Timers (Constant, Uniform Random, Gaussian Random, Poisson Random, Constant Throughput, Precise Throughput)\n"
            + "- Listeners (View Results Tree, Aggregate Report, Summary Report, Backend Listener, Response Time Graph)\n"
            + "- Test Fragments and Test Plan structure\n"
            + "\n\n"
            + "## KEY PLUGINS AND EXTENSIONS:\n"
            + "- Suggest relevant JMeter plugins if you find useful to accomplish the task\n"
            + "\n\n"
            + "## GUIDELINES:\n"
            + "1. Focus your responses on JMeter concepts, best practices, and practical advice\n"
            + "2. Provide concise, accurate information about JMeter elements\n"
            + "3. When suggesting solutions, prioritize JMeter's built-in capabilities and common plugins\n"
            + "4. Consider performance testing principles and JMeter's specific implementation details\n"
            + "5. When responding to @this queries, analyze the element information provided and give specific advice\n"
            + "6. Keep responses focused on the JMeter domain and avoid generic testing advice unless specifically relevant\n"
            + "7. Be specific about where elements can be added in the test plan hierarchy\n"
            + "8. Always consider test plan maintainability and performance overhead when giving recommendations\n"
            + "9. Highlight potential pitfalls or memory issues in suggested configurations\n"
            + "10. Explain correlation techniques for dynamic data handling in test scripts\n"
            + "11. Recommend appropriate load generation and monitoring strategies based on testing goals\n"
            + "\n\n"
            + "## PROGRAMMING LANGUAGES:\n"
            + "1. Focus on Groovy language by default for scripting (JSR223 elements)\n"
            + "2. Second focus on Java language\n"
            + "3. Provide regular expression patterns when needed for extractors and assertions\n"
            + "\n\n"
            + "## TEST EXECUTION AND ANALYSIS:\n"
            + "1. Help interpret test results and metrics from JMeter reports\n"
            + "2. Guide on appropriate command-line options for test execution\n"
            + "3. Explain how to set up distributed testing environments\n"
            + "4. Advise on test data preparation and management\n"
            + "5. Provide guidance on CI/CD integration for automated performance testing\n"
            + "\n\n"
            + "## TERMINOLOGY AND CONVENTIONS:\n"
            + "- Use official JMeter terminology from Apache documentation\n"
            + "- Refer to JMeter elements by their exact names as shown in JMeter GUI\n"
            + "- Use proper capitalization for JMeter components (e.g., \"Thread Group\" not \"thread group\")\n"
            + "- Reference Apache JMeter User Manual when providing detailed explanations\n"
            + "\n\n"
            + "Always provide practical, actionable advice that users can immediately apply to their JMeter test plans. Format your responses with clear sections and code examples when applicable.\n"
            + "\n"
            + "When describing script components or configuration, use proper formatting:\n"
            + "- Code blocks for scripts and commands\n"
            + "- Bullet points for steps and options\n"
            + "- Tables for comparing options when appropriate\n"
            + "- Bold for element names and important concepts\n"
            + "\n"
            + "Version: JMeter 5.6+ (Also support questions about older versions from 3.0+)";

    public BedrockService() {
        this.objectMapper = new ObjectMapper();
        
        // Default history size of 10, can be configured through jmeter.properties
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("bedrock.max.history.size", "10"));

        // Initialize AWS credentials and client
        String accessKey = AiConfig.getProperty("bedrock.access.key", "");
        String secretKey = AiConfig.getProperty("bedrock.secret.key", "");
        String region = AiConfig.getProperty("bedrock.region", "us-east-1");

        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            log.error("AWS credentials not configured. Please set bedrock.access.key and bedrock.secret.key in jmeter.properties");
            throw new IllegalStateException("AWS credentials not configured");
        }

        AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(accessKey, secretKey);
        
        this.client = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .build();

        // Get default model from properties
        this.currentModelId = AiConfig.getProperty("bedrock.model", "anthropic.claude-3-sonnet-20240229-v1:0");
        this.temperature = Float.parseFloat(AiConfig.getProperty("bedrock.temperature", "0.5"));
        this.maxTokens = Long.parseLong(AiConfig.getProperty("bedrock.max.tokens", "1024"));

        // Load system prompt from properties or use default
        try {
            systemPrompt = AiConfig.getProperty("bedrock.system.prompt", DEFAULT_JMETER_SYSTEM_PROMPT);

            if (systemPrompt == null) {
                log.warn("System prompt is null, using default");
                systemPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
            }

            log.info("Loaded system prompt from properties (length: {})", systemPrompt.length());
            log.info("System prompt (first 100 chars): {}",
                    systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
        } catch (Exception e) {
            log.error("Error loading system prompt, using default", e);
            systemPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
        }

        log.info("BedrockService initialized with model: {}, region: {}", currentModelId, region);
    }

    public BedrockRuntimeClient getClient() {
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
        log.info("Sending message to Bedrock: {}", message);
        return generateResponse(Collections.singletonList(message));
    }

    public String generateResponse(List<String> conversation) {
        try {
            log.info("Generating response for conversation with {} messages", conversation.size());

            // Ensure a model is set
            if (currentModelId == null || currentModelId.isEmpty()) {
                currentModelId = "anthropic.claude-3-sonnet-20240229-v1:0";
                log.warn("No model was set, defaulting to: {}", currentModelId);
            }

            // Ensure a temperature is set
            if (temperature < 0 || temperature > 1) {
                temperature = 0.7f;
                log.warn("Invalid temperature value ({}), defaulting to: {}", temperature, 0.7f);
            }

            log.info("Generating response using model: {} and temperature: {}", currentModelId, temperature);

            // Check if this is the first message in a conversation
            boolean isFirstMessage = !systemPromptInitialized;
            if (isFirstMessage) {
                log.info("Using system prompt (first 100 chars): {}",
                        systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
                systemPromptInitialized = true;
            } else {
                log.info("Using previously initialized conversation with system prompt");
            }

            // Limit conversation history to avoid token limits
            List<String> limitedConversation = conversation;
            if (conversation.size() > maxHistorySize) {
                limitedConversation = conversation.subList(conversation.size() - maxHistorySize, conversation.size());
                log.info("Limiting conversation to last {} messages", limitedConversation.size());
            }

            // Build the Bedrock request payload
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("anthropic_version", "bedrock-2023-05-31");
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);

            // Add system prompt if this is the first message
            if (isFirstMessage) {
                requestBody.put("system", systemPrompt);
                log.info("Including system prompt in request (length: {})", systemPrompt.length());
            } else {
                log.info("Skipping system prompt to save tokens (already sent in previous messages)");
            }

            // Add messages from the conversation history
            ArrayNode messages = objectMapper.createArrayNode();
            for (int i = 0; i < limitedConversation.size(); i++) {
                String msg = limitedConversation.get(i);
                ObjectNode messageNode = objectMapper.createObjectNode();
                
                if (i % 2 == 0) {
                    // User messages
                    messageNode.put("role", "user");
                } else {
                    // Assistant messages
                    messageNode.put("role", "assistant");
                }
                
                messageNode.put("content", msg);
                messages.add(messageNode);
            }
            requestBody.set("messages", messages);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            log.info("Request parameters: maxTokens={}, temperature={}, model={}, messagesCount={}",
                    maxTokens, temperature, currentModelId, limitedConversation.size());

            // Make the Bedrock API call
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(currentModelId)
                    .body(SdkBytes.fromUtf8String(requestBodyJson))
                    .build();

            InvokeModelResponse response = client.invokeModel(request);
            String responseBody = response.body().asUtf8String();
            
            log.info("Received response from Bedrock: {}", responseBody);

            // Parse the response
            ObjectNode responseJson = (ObjectNode) objectMapper.readTree(responseBody);
            String responseText = responseJson.get("content").get(0).get("text").asText();

            // Extract usage information if available
            ObjectNode usage = (ObjectNode) responseJson.get("usage");
            long inputTokens = usage != null ? usage.get("input_tokens").asLong() : estimateTokens(String.join(" ", limitedConversation));
            long outputTokens = usage != null ? usage.get("output_tokens").asLong() : estimateTokens(responseText);

            if (isFirstMessage && systemPrompt != null && !systemPrompt.isEmpty()) {
                inputTokens += estimateTokens(systemPrompt);
            }

            // Record the usage
            try {
                BedrockUsage.getInstance().recordUsage(
                        currentModelId,
                        inputTokens,
                        outputTokens);
                log.info("Recorded token usage: {} input, {} output", inputTokens, outputTokens);
            } catch (Exception e) {
                log.error("Failed to record token usage", e);
            }

            return responseText;
        } catch (Exception e) {
            log.error("Error generating response", e);
            String errorMessage = extractUserFriendlyErrorMessage(e);
            return "Error: " + errorMessage;
        }
    }

    /**
     * Estimates the number of tokens for a given text.
     * This is a rough estimate using a heuristic of characters/4.
     * 
     * @param text The text to estimate tokens for
     * @return Estimated token count
     */
    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
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

        // Check for common AWS/Bedrock errors
        if (errorMessage != null && errorMessage.contains("AccessDenied")) {
            return "Access denied. Please check your AWS credentials and permissions for Bedrock.";
        }

        if (errorMessage != null && errorMessage.contains("ThrottlingException")) {
            return "Rate limit exceeded. Please try again later.";
        }

        if (errorMessage != null && errorMessage.contains("ModelNotFound")) {
            return "The selected model was not found. Please check the model ID and ensure it's available in your region.";
        }

        if (errorMessage != null && errorMessage.contains("ValidationException")) {
            return "Invalid request parameters. Please check your configuration.";
        }

        if (errorMessage != null && errorMessage.contains("ServiceUnavailable")) {
            return "Bedrock service is temporarily unavailable. Please try again later.";
        }

        // For other errors, provide a cleaner message
        if (errorMessage != null) {
            return errorMessage;
        }

        return "An error occurred while communicating with AWS Bedrock. Please try again later.";
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
        return "AWS Bedrock Claude";
    }
}