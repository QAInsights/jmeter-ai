package org.qainsights.jmeter.ai.utils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ModelListPage;
import com.anthropic.models.models.ModelListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.Model;

import org.qainsights.jmeter.ai.service.OllamaAiService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Models {
    private static final Logger log = LoggerFactory.getLogger(Models.class);

    /**
     * Load all available models with provider prefixes for use in the model selector.
     * Anthropic models are returned as-is; OpenAI models are prefixed with "openai:";
     * Ollama models are prefixed with "ollama:".
     * @param anthropicClient Anthropic client
     * @param openAiClient OpenAI client
     * @param ollamaService OllamaAiService
     * @return List of prefixed model IDs
     */
    public static List<String> loadAllModels(AnthropicClient anthropicClient,
        OpenAIClient openAiClient,
        OllamaAiService ollamaService) {
        List<String> allModels = new ArrayList<>();

        // Get Anthropic models
        try {
            ModelListPage anthropicModels = getAnthropicModels(anthropicClient);
            if (anthropicModels != null && anthropicModels.data() != null) {
                for (ModelInfo model : anthropicModels.data()) {
                    allModels.add(model.id());
                    log.debug("Added Anthropic model: {}", model.id());
                }
                log.info("Added {} Anthropic models", anthropicModels.data().size());
            }
        } catch (Exception e) {
            log.error("Error loading Anthropic models: {}", e.getMessage(), e);
        }

        // Add OpenAI models
        try {
            com.openai.models.ModelListPage openAiModels = getOpenAiModels(openAiClient);
            if (openAiModels != null && openAiModels.data() != null) {
                for (Model openAiModel : openAiModels.data()) {
                    if (openAiModel.id().startsWith("gpt") &&
                            !openAiModel.id().contains("audio") &&
                            !openAiModel.id().contains("tts") &&
                            !openAiModel.id().contains("whisper") &&
                            !openAiModel.id().contains("davinci") &&
                            !openAiModel.id().contains("search") &&
                            !openAiModel.id().contains("transcribe") &&
                            !openAiModel.id().contains("realtime") &&
                            !openAiModel.id().contains("instruct")) {
                        String modelId = "openai:" + openAiModel.id();
                        allModels.add(modelId);
                        log.debug("Added OpenAI model to selector: {}", openAiModel.id());
                    }
                }
                log.info("Added OpenAI models to selector");
            }
        } catch (Exception e) {
            log.error("Error adding OpenAI models: {}", e.getMessage(), e);
        }

        // Add Ollama models
        try {
            List<io.github.ollama4j.models.response.Model> ollamaModels = ollamaService.listModels();
            if (ollamaModels != null) {
                for (io.github.ollama4j.models.response.Model ollamaModel : ollamaModels) {
                    String modelId = "ollama:" + ollamaModel.getName();
                    allModels.add(modelId);
                    log.debug("Added Ollama model to selector: {}", ollamaModel.getName());
                }
                log.info("Added {} Ollama models to selector", ollamaModels.size());
            }
        } catch (Exception e) {
            log.error("Error adding Ollama models: {}", e.getMessage(), e);
        }

        return allModels;
    }

    /**
     * Get a combined list of model IDs from both Anthropic and OpenAI
     * @param anthropicClient Anthropic client
     * @param openAiClient OpenAI client
     * @param ollamaService OllamaAiService
     * @return List of model IDs
     */
    public static List<String> getModelIds(AnthropicClient anthropicClient, 
        OpenAIClient openAiClient,
        OllamaAiService ollamaService) {
        List<String> modelIds = new ArrayList<>();
        
        try {
            // Get Anthropic models
            List<String> anthropicModels = getAnthropicModelIds(anthropicClient);
            if (anthropicModels != null) {
                modelIds.addAll(anthropicModels);
            }
            
            // Get OpenAI models
            List<String> openAiModels = getOpenAiModelIds(openAiClient);
            if (openAiModels != null) {
                modelIds.addAll(openAiModels);
            }

            // Get Ollama models
            List<String> ollamaModels = getOllamaModelIds(ollamaService);
            if (ollamaModels != null) {
                modelIds.addAll(ollamaModels);
            }
            
            log.info("Combined {} models from Anthropic and OpenAI", modelIds.size());
            return modelIds;
        } catch (Exception e) {
            log.error("Error combining models: {}", e.getMessage(), e);
            return modelIds; // Return whatever we have, even if empty
        }
    }
    
    private static List<String> getOllamaModelIds(OllamaAiService ollamaService) {
        try {
            List<io.github.ollama4j.models.response.Model> ollamaModels = ollamaService.listModels();
            log.info("Successfully retrieved {} models from Ollama API", ollamaModels.size());
            return ollamaModels.stream()
                    .map(io.github.ollama4j.models.response.Model::getName)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching models from Ollama API: {}", e.getMessage(), e);
            return null;
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
     * Get OpenAI models as ModelListPage
     * @param client OpenAI client
     * @return OpenAI ModelListPage
     */
    public static com.openai.models.ModelListPage getOpenAiModels(OpenAIClient client) {
        try {
            log.info("Fetching available models from OpenAI API");
            client = OpenAIOkHttpClient.builder()
                    .apiKey(AiConfig.getProperty("openai.api.key", "YOUR_API_KEY"))
                    .build();            

            com.openai.models.ModelListPage models = client.models().list();
            
            log.info("Successfully retrieved {} models from OpenAI API", models.data().size());
            for (Model model : models.data()) {
                log.debug("Available OpenAI model: {}", model.id());
            }
            return models;
        } catch (Exception e) {
            log.error("Error fetching models from OpenAI API: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Get OpenAI model IDs as a List of Strings
     * @param client OpenAI client
     * @return List of model IDs
     */
    public static List<String> getOpenAiModelIds(OpenAIClient client) {
        com.openai.models.ModelListPage models = getOpenAiModels(client);
        if (models != null && models.data() != null) {
            // Return the list of GPT models only, excluding audio and TTS models
            return models.data().stream()
                    .filter(model -> model.id().startsWith("gpt")) // Include only GPT models
                    .filter(model -> !model.id().contains("audio")) // Exclude audio models
                    .filter(model -> !model.id().contains("tts")) // Exclude text-to-speech models
                    .filter(model -> !model.id().contains("whisper")) // Exclude whisper models
                    .filter(model -> !model.id().contains("davinci")) // Exclude Davinci models
                    .filter(model -> !model.id().contains("search")) // Exclude search models
                    .filter(model -> !model.id().contains("transcribe")) // Exclude transcribe models
                    .filter(model -> !model.id().contains("realtime")) // Exclude realtime models
                    .filter(model -> !model.id().contains("instruct")) // Exclude instruct models
                    .map(com.openai.models.Model::id)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
