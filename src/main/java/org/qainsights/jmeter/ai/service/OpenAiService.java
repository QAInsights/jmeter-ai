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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
// import org.qainsights.jmeter.ai.usage.OpenAiUsage; // OpenAiUsage is no longer used

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final String CUSTOM_API_URL = "https://api.lab45.ai/v1.1/skills/completion/query"; // Updated API URL
    
    private transient CloseableHttpClient httpClient;
    private transient Gson gson;
    private String customApiKey;

    // New fields for Lab45 API
    private String currentModelName = "gpt-4o"; // Default model for Lab45
    private int currentMaxOutputTokens = 4096;  // Default tokens for gpt-4o

    // Fields like systemPromptInitialized, maxHistorySize, currentModelId (old), temperature (old), systemPrompt, maxTokens (old)
    // are being deprecated or repurposed.
    private boolean systemPromptInitialized = false; // Potentially unused
    private int maxHistorySize; // Potentially unused
    private String oldCurrentModelId; // Renamed to avoid confusion, will be deprecated
    private float oldTemperature; // Renamed to avoid confusion, will be deprecated
    private String systemPrompt; // Potentially unused
    private long oldMaxTokens; // Renamed to avoid confusion, will be deprecated
    private static final String DEFAULT_JMETER_SYSTEM_PROMPT = "JMeter expert assistant (prompt unused by Lab45 API)";


    public OpenAiService() {
        this.httpClient = HttpClients.createDefault();
        this.gson = new Gson();
        this.customApiKey = AiConfig.getProperty("custom.api.key", ""); // This should now be Lab45 API Key

        // Initialize legacy fields from config for now, but mark as deprecated or log their non-use.
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("openai.max.history.size", "10"));
        this.oldCurrentModelId = AiConfig.getProperty("openai.default.model", "gpt-4o"); // Store in old field
        this.oldTemperature = Float.parseFloat(AiConfig.getProperty("openai.temperature", "0.7")); // Store in old field
        this.systemPrompt = AiConfig.getProperty("openai.system.prompt", DEFAULT_JMETER_SYSTEM_PROMPT);
        this.oldMaxTokens = Long.parseLong(AiConfig.getProperty("openai.max.tokens", "4096")); // Store in old field
        
        // Set new Lab45 specific defaults (can be overridden by setCurrentModelAndTokens)
        this.currentModelName = AiConfig.getProperty("lab45.default.model", "gpt-4o");
        this.currentMaxOutputTokens = Integer.parseInt(AiConfig.getProperty("lab45.default.max_tokens", "4096"));

        log.info("OpenAiService initialized for Lab45 AI API using model: {} with max tokens: {}", this.currentModelName, this.currentMaxOutputTokens);
        if (this.customApiKey.isEmpty()) {
            log.warn("Lab45 API key (custom.api.key) is NOT configured. API calls will likely fail.");
        } else {
            log.info("Lab45 API key loaded successfully.");
        }
        log.info("Legacy OpenAI configurations (mostly unused): maxHistorySize={}, oldCurrentModelId={}, oldTemperature={}, systemPrompt (length)={}, oldMaxTokens={}",
                this.maxHistorySize, this.oldCurrentModelId, this.oldTemperature, (this.systemPrompt != null ? this.systemPrompt.length() : "null"), this.oldMaxTokens);
    }

    /**
     * Sets the model name and max output tokens for the Lab45 API.
     * @param modelName Name of the model to use (e.g., "gpt-4o", "claude-3-opus").
     * @param maxOutputTokens Maximum number of tokens for the output.
     */
    public void setCurrentModelAndTokens(String modelName, int maxOutputTokens) {
        this.currentModelName = modelName;
        this.currentMaxOutputTokens = maxOutputTokens;
        log.info("Set Lab45 model to: {} with max output tokens: {}", modelName, maxOutputTokens);
    }

    @Deprecated
    public void setModel(String modelId) {
        this.oldCurrentModelId = modelId;
        log.warn("@Deprecated setModel({}) called. Use setCurrentModelAndTokens. This only updates a legacy field.", modelId);
        // For potential partial compatibility, if other parts of code call this with a Lab45 model name.
        if (modelId != null && !modelId.isEmpty()) {
            this.currentModelName = modelId; // Update new field as well
            log.info("Updated currentModelName to {} via deprecated setModel", modelId);
        }
    }

    @Deprecated
    public String getCurrentModel() {
        log.warn("@Deprecated getCurrentModel() called. Returning legacy modelId field: {}. For active model, consider a new getter for currentModelName.", oldCurrentModelId);
        return oldCurrentModelId;
    }

    @Deprecated
    public void setTemperature(float temperature) {
        this.oldTemperature = temperature;
        log.warn("@Deprecated setTemperature({}) called. Temperature is hardcoded (0.3) in the Lab45 API request payload.", temperature);
    }

    @Deprecated
    public float getTemperature() {
        log.warn("@Deprecated getTemperature() called. Temperature is hardcoded (0.3) in the Lab45 API request. Returning stored legacy value: {}", oldTemperature);
        return oldTemperature;
    }

    @Deprecated
    public void setMaxTokens(long maxTokens) {
        this.oldMaxTokens = maxTokens;
        log.warn("@Deprecated setMaxTokens({}) called. Use setCurrentModelAndTokens. Max tokens is managed by currentMaxOutputTokens.", maxTokens);
        if (maxTokens <= Integer.MAX_VALUE && maxTokens > 0) {
             this.currentMaxOutputTokens = (int) maxTokens; // Update new field if possible
             log.info("Updated currentMaxOutputTokens to {} via deprecated setMaxTokens", maxTokens);
        }
    }

    @Deprecated
    public long getMaxTokens() {
        log.warn("@Deprecated getMaxTokens() called. Returning legacy maxTokens field: {}. For active setting, consider a new getter for currentMaxOutputTokens.", oldMaxTokens);
        return oldMaxTokens;
    }

    /**
     * Resets the system prompt initialization flag. This is a legacy method
     * and likely not relevant for the Lab45 API.
     */
    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false; // This field is largely unused now
        log.info("resetSystemPromptInitialization() called. This flag is not actively used by the Lab45 API implementation.");
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

        String userMessage = "";
        if (conversation != null && !conversation.isEmpty()) {
            userMessage = conversation.get(conversation.size() - 1);
        }

        if (customApiKey == null || customApiKey.isEmpty()) {
            log.error("Lab45 API Key is not set. Cannot make API call.");
            return "Error: Lab45 API Key is not configured.";
        }

        // Message object
        Map<String, String> messageObject = new HashMap<>();
        messageObject.put("content", userMessage);
        messageObject.put("role", "user");

        // Messages list
        List<Map<String, String>> messagesList = new ArrayList<>();
        messagesList.add(messageObject);

        // Skill parameters
        Map<String, Object> skillParameters = new HashMap<>();
        skillParameters.put("max_output_tokens", this.currentMaxOutputTokens);
        skillParameters.put("temperature", 0.3); // Hardcoded as per Lab45 spec in this context
        skillParameters.put("return_sources", true);
        skillParameters.put("model_name", this.currentModelName);

        // Main payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("messages", messagesList);
        payload.put("search_provider", "Bing"); // As per Lab45 spec
        payload.put("stream_response", false);  // As per Lab45 spec for non-streaming
        payload.put("skill_parameters", skillParameters);

        String jsonPayload = gson.toJson(payload);

        log.info("Generating response for user message (last in conversation) using Lab45 API model: {}", this.currentModelName);
        log.debug("Request payload to Lab45 API: {}", jsonPayload);

        HttpPost postRequest = new HttpPost(CUSTOM_API_URL); // URL is now Lab45 URL
        postRequest.setHeader("Authorization", "Bearer " + customApiKey);
        postRequest.setHeader("Content-Type", "application/json");
        postRequest.setHeader("Accept", "text/event-stream"); // Lab45 specific header
        // postRequest.setHeader("Custom-Header", "custom-value"); // This was for old custom API, removing unless Lab45 needs it

        try {
            postRequest.setEntity(new StringEntity(jsonPayload));

            try (CloseableHttpResponse response = httpClient.execute(postRequest)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                int statusCode = response.getStatusLine().getStatusCode();
                log.info("Received response from Lab45 API. Status: {}, Body: {}", statusCode, responseBody);

                if (statusCode >= 200 && statusCode < 300) {
                    JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                    String generatedText = "";

                    if (jsonResponse.has("choices")) {
                        JsonObject firstChoice = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (firstChoice.has("message") && firstChoice.getAsJsonObject("message").has("content")) {
                            generatedText = firstChoice.getAsJsonObject("message").get("content").getAsString();
                        }
                    } else if (jsonResponse.has("generated_text")) {
                        generatedText = jsonResponse.get("generated_text").getAsString();
                    } else if (jsonResponse.has("message") && jsonResponse.getAsJsonObject("message").has("content")) {
                        generatedText = jsonResponse.getAsJsonObject("message").get("content").getAsString();
                    } else {
                        log.warn("Could not find standard 'content' or 'generated_text' in Lab45 response. Response body: {}", responseBody);
                        if (jsonResponse.has("response")) generatedText = jsonResponse.get("response").getAsString();
                        else if (jsonResponse.has("text")) generatedText = jsonResponse.get("text").getAsString();
                        else generatedText = "Could not parse AI response: " + responseBody.substring(0, Math.min(responseBody.length(), 200));
                    }
                    return generatedText;
                } else {
                    log.error("Lab45 API request failed with status code: {} and body: {}", statusCode, responseBody);
                    return "Error: API request failed with status " + statusCode + ". Response: " + responseBody.substring(0, Math.min(responseBody.length(), 500));
                }
            }
        } catch (Exception e) { // Catching general Exception for broader issues including JsonSyntaxException
            log.error("Error during communication with Lab45 AI API or parsing its response", e);
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
     * Simplified for Apache HttpClient and Gson usage, now for Lab45 API.
     * 
     * @param e The exception to extract the error message from
     * @return A user-friendly error message
     */
    private String extractUserFriendlyErrorMessage(Exception e) {
        String technicalMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.debug("Extracting user-friendly error message from exception (Lab45 API context)", e);

        if (e instanceof IOException) { // Covers network issues, connection refused, etc.
            return "A network error occurred while communicating with the Lab45 AI API: " + technicalMessage;
        } else if (e instanceof com.google.gson.JsonSyntaxException) {
            return "Error parsing JSON response from the Lab45 AI API: " + technicalMessage;
        } else if (e instanceof org.apache.http.conn.HttpHostConnectException) {
            return "Could not connect to the Lab45 AI API host: " + technicalMessage;
        }
        // Add more specific Apache HttpClient or other exception types if needed

        // Generic fallback
        return "An unexpected error occurred while processing your request with the Lab45 AI API: " + technicalMessage + ". Check logs for more details.";
    }

    public String getName() {
        return "Lab45AI"; // Updated service name
    }
}
