package org.qainsights.jmeter.ai.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
// import org.qainsights.jmeter.ai.usage.OpenAiUsage; // OpenAiUsage is no longer used

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final String CUSTOM_API_URL = "https://custom-ai.com/api/generate";
    
    private transient CloseableHttpClient httpClient;
    private transient Gson gson;
    private String customApiKey;

    // Fields like systemPromptInitialized, maxHistorySize, currentModelId, temperature, systemPrompt, maxTokens
    // are largely unused with the new custom API implementation as values are hardcoded or not applicable.
    // They are kept for now to minimize structural changes if they are part of the AiService interface,
    // but their direct functionality in API calls is reduced/removed.
    private boolean systemPromptInitialized = false; 
    private int maxHistorySize; 
    private String currentModelId; 
    private float temperature; 
    private String systemPrompt; 
    private long maxTokens; 
    private static final String DEFAULT_JMETER_SYSTEM_PROMPT = "JMeter expert assistant (prompt unused by custom API)";


    public OpenAiService() {
        this.httpClient = HttpClients.createDefault();
        this.gson = new Gson();
        this.customApiKey = AiConfig.getProperty("custom.api.key", "");

        // Initialize (but mostly unused) OpenAI-era fields from config, with logging
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("openai.max.history.size", "10"));
        this.currentModelId = AiConfig.getProperty("openai.default.model", "gpt-4o");
        this.temperature = Float.parseFloat(AiConfig.getProperty("openai.temperature", "0.7"));
        this.systemPrompt = AiConfig.getProperty("openai.system.prompt", DEFAULT_JMETER_SYSTEM_PROMPT);
        this.maxTokens = Long.parseLong(AiConfig.getProperty("openai.max.tokens", "4096"));
        
        log.info("OpenAiService initialized for Custom AI API.");
        if (this.customApiKey.isEmpty()) {
            log.warn("Custom API key is NOT configured. API calls will likely fail.");
        } else {
            log.info("Custom API key loaded successfully.");
        }
        log.info("Legacy OpenAI configurations (mostly unused): maxHistorySize={}, currentModelId={}, temperature={}, systemPrompt (length)={}, maxTokens={}",
                this.maxHistorySize, this.currentModelId, this.temperature, (this.systemPrompt != null ? this.systemPrompt.length() : "null"), this.maxTokens);
    }

    public void setModel(String modelId) {
        this.currentModelId = modelId;
        log.warn("setModel({}) called, but model selection is not actively used by the custom API implementation.", modelId);
    }

    public String getCurrentModel() {
        log.warn("getCurrentModel() called, but model selection is not actively used by the custom API implementation. Returning stored value: {}", currentModelId);
        return currentModelId;
    }

    public void setTemperature(float temperature) {
        this.temperature = temperature;
        log.warn("setTemperature({}) called, but temperature is hardcoded (0.3) in the custom API request.", temperature);
    }

    public float getTemperature() {
        log.warn("getTemperature() called, but temperature is hardcoded (0.3) in the custom API request. Returning stored value: {}", temperature);
        return temperature;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
        log.warn("setMaxTokens({}) called, but max_tokens is hardcoded (256) in the custom API request.", maxTokens);
    }

    public long getMaxTokens() {
        log.warn("getMaxTokens() called, but max_tokens is hardcoded (256) in the custom API request. Returning stored value: {}", maxTokens);
        return maxTokens;
    }

    /**
     * Resets the system prompt initialization flag. This is a legacy method from OpenAI
     * and may not be relevant for the custom API.
     */
    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false;
        log.info("resetSystemPromptInitialization() called. This flag is not actively used by the custom API implementation.");
    }

    public String sendMessage(String message) {
        log.info("Sending message to Custom AI API: {}", message);
        return generateResponse(java.util.Collections.singletonList(message));
    }

    public String generateResponse(List<String> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            log.warn("Conversation is empty. Cannot generate response.");
            return "Error: Conversation is empty.";
        }

        String userMessage = conversation.get(conversation.size() - 1);

        if (customApiKey == null || customApiKey.isEmpty()) {
            log.error("Custom API Key is not set. Cannot make API call.");
            return "Error: Custom API Key is not configured.";
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", userMessage);
        payload.put("max_tokens", 256); // Hardcoded as per requirement
        payload.put("temperature", 0.3); // Hardcoded as per requirement
        String jsonPayload = gson.toJson(payload);

        log.info("Generating response for user message (last in conversation): {}", userMessage);
        log.debug("Request payload to Custom AI API: {}", jsonPayload);

        HttpPost postRequest = new HttpPost(CUSTOM_API_URL);
        postRequest.setHeader("Authorization", "Bearer " + customApiKey);
        postRequest.setHeader("Content-Type", "application/json");
        postRequest.setHeader("Custom-Header", "custom-value"); // Specific custom header

        try {
            postRequest.setEntity(new StringEntity(jsonPayload));

            try (CloseableHttpResponse response = httpClient.execute(postRequest)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                int statusCode = response.getStatusLine().getStatusCode();
                log.info("Received response from custom API. Status: {}, Body: {}", statusCode, responseBody);

                if (statusCode >= 200 && statusCode < 300) {
                    JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                    String generatedText = "";
                    if (jsonResponse.has("generated_text")) {
                        generatedText = jsonResponse.get("generated_text").getAsString();
                    } else {
                        log.warn("'generated_text' field not found in API response: {}", responseBody);
                        generatedText = "Error: 'generated_text' not found in API response.";
                    }
                    return generatedText;
                } else {
                    log.error("Custom API request failed with status code: {} and body: {}", statusCode, responseBody);
                    return "Error: API request failed with status " + statusCode + ". Response: " + responseBody;
                }
            }
        } catch (Exception e) { // Catching general Exception for broader issues including JsonSyntaxException
            log.error("Error during communication with Custom AI API or parsing its response", e);
            String errorMessage = extractUserFriendlyErrorMessage(e);
            return "Error: " + errorMessage;
        }
    }

    /**
     * Generates a response from the AI using the specified model.
     * For the custom API, this might throw UnsupportedOperationException if model switching is not supported
     * or simply call the main generateResponse method, ignoring the model.
     * 
     * @param conversation The conversation history
     * @param model        The specific model to use for this request (currently ignored)
     * @return The AI's response
     */
    public String generateResponse(List<String> conversation, String model) {
        log.warn("Generating response with custom API. The 'model' parameter ('{}') is currently ignored.", model);
        // For now, it calls the main generateResponse method, ignoring the model parameter.
        // Alternatively, if the custom API does not support model selection via this implementation:
        // throw new UnsupportedOperationException("Model selection is not supported with the current custom API implementation.");
        return generateResponse(conversation);
    }

    /**
     * Extracts a user-friendly error message from an exception.
     * Simplified for Apache HttpClient and Gson usage.
     * 
     * @param e The exception to extract the error message from
     * @return A user-friendly error message
     */
    private String extractUserFriendlyErrorMessage(Exception e) {
        String technicalMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.debug("Extracting user-friendly error message from exception", e);

        if (e instanceof IOException) { // Covers network issues, connection refused, etc.
            return "A network error occurred while communicating with the Custom AI API: " + technicalMessage;
        } else if (e instanceof com.google.gson.JsonSyntaxException) {
            return "Error parsing JSON response from the Custom AI API: " + technicalMessage;
        } else if (e instanceof org.apache.http.conn.HttpHostConnectException) {
            return "Could not connect to the Custom AI API host: " + technicalMessage;
        }
        // Add more specific Apache HttpClient or other exception types if needed

        // Generic fallback
        return "An unexpected error occurred while processing your request with the Custom AI API: " + technicalMessage + ". Check logs for more details.";
    }

    public String getName() {
        return "CustomAI";
    }
}
