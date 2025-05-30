package org.nfe.jmeter.ai.utils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import com.anthropic.models.ModelInfo;
import com.anthropic.models.ModelListPage;
import com.anthropic.models.ModelListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// import com.openai.client.OpenAIClient; // OpenAI SDK removed
// import com.openai.client.okhttp.OpenAIOkHttpClient; // OpenAI SDK removed
// import com.openai.models.Model; // OpenAI SDK removed

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Models {
    private static final Logger log = LoggerFactory.getLogger(Models.class);

    /**
     * Inner class or Record to store model details.
     */
    public static class ModelDetail {
        public final String displayName; // e.g., "GPT-4o - 4096 tokens"
        public final String modelId;     // e.g., "gpt-4o"
        public final int maxTokens;

        public ModelDetail(String displayName, String modelId, int maxTokens) {
            this.displayName = displayName;
            this.modelId = modelId;
            this.maxTokens = maxTokens;
        }

        @Override
        public String toString() { // Used by JComboBox by default
            return displayName;
        }
    }

    private static final List<ModelDetail> AVAILABLE_LAB45_MODELS = Arrays.asList(
        new ModelDetail("GPT-4o - 4096 tokens", "gpt-4o", 4096), // Default first
        new ModelDetail("GPT-3.5 Turbo 16k - 4096 tokens", "gpt-3.5-turbo-16k", 4096),
        new ModelDetail("GPT-4 - 4096 tokens", "gpt-4", 4096),
        new ModelDetail("Amazon Titan-TG1-Large - 2048 tokens", "amazon.titan-tg1-large", 2048),
        new ModelDetail("Gemini Pro - 8192 tokens", "gemini-pro", 8192),
        new ModelDetail("Gemini 1.5 Pro - 8192 tokens", "gemini-1.5-pro", 8192),
        new ModelDetail("Gemini 1.5 Flash - 8192 tokens", "gemini-1.5-flash", 8192),
        new ModelDetail("Jais 30B Chat - 2048 tokens", "jais-30b-chat", 2048)
    );
    
    /**
     * Get a combined list of model display names from Anthropic and Lab45 AI.
     * Used by AiChatPanel for its JComboBox<String>.
     * @param anthropicClient Anthropic client (can be null if Anthropic is not configured/used)
     * @return List of model display names
     */
    public static List<String> getModelDisplayNames(AnthropicClient anthropicClient) {
        List<String> displayNames = new ArrayList<>();
        
        try {
            // Get Anthropic model IDs (these are display names already)
            if (anthropicClient != null) { // Check if client is provided
                List<String> anthropicModelIds = getAnthropicModelIds(anthropicClient);
                if (anthropicModelIds != null) {
                    displayNames.addAll(anthropicModelIds);
                }
            }
            
            // Get Lab45 model display names
            for (ModelDetail detail : AVAILABLE_LAB45_MODELS) {
                // Prefixing to distinguish in UI, consistent with how AiChatPanel processes them
                displayNames.add("custom:" + detail.displayName); 
            }
            
            log.info("Combined {} model display names.", displayNames.size());
            return displayNames;
        } catch (Exception e) {
            log.error("Error combining model display names: {}", e.getMessage(), e);
            // Fallback to just Lab45 models if Anthropic fails or isn't configured
            return AVAILABLE_LAB45_MODELS.stream().map(md -> "custom:" + md.displayName).collect(Collectors.toList());
        }
    }

    /**
     * Get Anthropic models as ModelListPage
     * @param client Anthropic client
     * @return ModelListPage containing Anthropic models
     */
    public static ModelListPage getAnthropicModels(AnthropicClient client) {
        try {
            log.info("Fetching available models from Anthropic API");
            client = AnthropicOkHttpClient.builder()
                    .apiKey(AiConfig.getProperty("anthropic.api.key", "YOUR_API_KEY"))
                    .build();

            ModelListParams modelListParams = ModelListParams.builder().build();
            ModelListPage models = client.models().list(modelListParams);
            
            log.info("Successfully retrieved {} models from Anthropic API", models.data().size());
            for (ModelInfo model : models.data()) {
                log.debug("Available Anthropic model: {}", model.id());
            }
            return models;
        } catch (Exception e) {
            log.error("Error fetching models from Anthropic API: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Get Anthropic model IDs as a List of Strings
     * @param client Anthropic client
     * @return List of model IDs
     */
    public static List<String> getAnthropicModelIds(AnthropicClient client) {
        ModelListPage models = getAnthropicModels(client);
        if (models != null && models.data() != null) {
            return models.data().stream()
                    .map(ModelInfo::id)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
    
    /**
     * Get OpenAI models as ModelListPage - This method is no longer functional
     * as OpenAI SDK is removed. It will return null.
     * @return null
     */
    // public static Object getOpenAiModels(/* OpenAIClient client - Removed */) { // Method removed as it's obsolete
    //     log.warn("getOpenAiModels called, but OpenAI SDK is removed. This method will return null.");
    //     return null; 
    // }
    
    /**
     * Get Lab45 model details.
     * @return List of ModelDetail objects for Lab45 models.
     */
    public static List<ModelDetail> getLab45ModelDetails() {
        log.info("Returning static list of Lab45 model details.");
        return AVAILABLE_LAB45_MODELS;
    }

    /**
     * Finds a Lab45 ModelDetail object by its display name.
     * @param displayName The display name of the model.
     * @return The ModelDetail object, or null if not found.
     */
    public static ModelDetail findModelDetailByDisplayName(String displayName) {
        for (ModelDetail detail : AVAILABLE_LAB45_MODELS) {
            if (detail.displayName.equals(displayName)) {
                return detail;
            }
        }
        log.warn("No ModelDetail found for displayName: {}", displayName);
        return null; 
    }
    
    /**
     * Finds a Lab45 ModelDetail object by its model ID.
     * @param modelId The model ID (e.g., "gpt-4o").
     * @return The ModelDetail object, or null if not found.
     */
    public static ModelDetail findModelDetailById(String modelId) {
        for (ModelDetail detail : AVAILABLE_LAB45_MODELS) {
            if (detail.modelId.equals(modelId)) {
                return detail;
            }
        }
        log.warn("No ModelDetail found for modelId: {}", modelId);
        return null;
    }

    /**
     * Gets the default Lab45 ModelDetail.
     * @return The default ModelDetail object.
     */
    public static ModelDetail getDefaultLab45ModelDetail() {
        // Assuming "gpt-4o" is the default, as specified
        for (ModelDetail detail : AVAILABLE_LAB45_MODELS) {
            if ("gpt-4o".equals(detail.modelId)) {
                return detail;
            }
        }
        // Fallback if gpt-4o is somehow not in the list (should not happen with static list)
        return AVAILABLE_LAB45_MODELS.isEmpty() ? null : AVAILABLE_LAB45_MODELS.get(0);
    }
}
