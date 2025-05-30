package org.qainsights.jmeter.ai.utils;

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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Models {
    private static final Logger log = LoggerFactory.getLogger(Models.class);

    /**
     * Get a combined list of model IDs from Anthropic and a placeholder for custom models.
     * @param anthropicClient Anthropic client
     * @return List of model IDs
     */
    public static List<String> getModelIds(AnthropicClient anthropicClient /*, OpenAIClient openAiClient - Removed */) {
        List<String> modelIds = new ArrayList<>();
        
        try {
            // Get Anthropic models
            List<String> anthropicModels = getAnthropicModelIds(anthropicClient);
            if (anthropicModels != null) {
                modelIds.addAll(anthropicModels);
            }
            
            // Add placeholder for Custom AI model (if applicable)
            // If custom AI service has a fixed model or model selection is not relevant,
            // this can be an empty list or a specific identifier.
            List<String> customModels = getOpenAiModelIds(); // Changed signature
            if (customModels != null) {
                modelIds.addAll(customModels);
            }
            
            log.info("Combined {} models from Anthropic and Custom AI placeholder", modelIds.size());
            return modelIds;
        } catch (Exception e) {
            log.error("Error combining models: {}", e.getMessage(), e);
            return modelIds; // Return whatever we have, even if empty
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
     * @param client OpenAI client (unused)
     * @return null
     */
    public static Object getOpenAiModels(/* OpenAIClient client - Removed */) { // Return type changed to Object
        log.warn("getOpenAiModels called, but OpenAI SDK is removed. This method will return null.");
        // This method previously fetched models from OpenAI.
        // Since the OpenAI SDK is removed, this functionality is no longer available.
        // Returning null or an empty list as appropriate for the calling context.
        // For now, let's assume null is acceptable if the caller handles it.
        return null; 
    }
    
    /**
     * Get OpenAI model IDs as a List of Strings.
     * Since the OpenAI SDK is removed, this now returns a predefined static list for the "custom AI".
     * @return List of model IDs (e.g., ["custom-model"])
     */
    public static List<String> getOpenAiModelIds(/* OpenAIClient client - Removed */) {
        log.info("getOpenAiModelIds called. OpenAI SDK removed. Returning static list for custom AI.");
        // Return a static list, e.g., a single model or an empty list
        // depending on whether the custom API supports model selection or has a fixed model.
        // For compilation and basic UI functionality, providing a placeholder:
        return Collections.singletonList("custom-model"); 
        // Alternatively, if no model selection is relevant for custom API:
        // return new ArrayList<>(); 
    }
}
