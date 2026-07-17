package org.qainsights.jmeter.ai.utils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ModelListPage;
import com.anthropic.models.models.ModelListParams;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.models.Model;
import org.qainsights.jmeter.ai.service.AiServiceHolder;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.service.GrokAiService;
import org.qainsights.jmeter.ai.service.BedrockAiService;
import org.qainsights.jmeter.ai.service.MetaMuseAiService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Models {
    private static final Logger log = LoggerFactory.getLogger(Models.class);

    /**
     * Load all available models with provider prefixes for use in the model selector.
     * Anthropic models are returned as-is; OpenAI models are prefixed with "openai:";
     * Ollama models are prefixed with "ollama:".
     *
     * @param serviceHolder Container holding all AI services
     * @return List of prefixed model IDs
     */
    public static List<String> loadAllModels(AiServiceHolder serviceHolder) {
        List<String> allModels = new ArrayList<>();
        if (serviceHolder == null) {
            return allModels;
        }

        // Get Anthropic models
        try {
            if (serviceHolder.getClaudeService() != null) {
                ModelListPage anthropicModels = getAnthropicModels(serviceHolder.getClaudeService().getClient());
                if (anthropicModels != null && anthropicModels.data() != null) {
                    for (ModelInfo model : anthropicModels.data()) {
                        allModels.add(model.id());
                        log.debug("Added Anthropic model: {}", model.id());
                    }
                    log.info("Added {} Anthropic models", anthropicModels.data().size());
                }
            }
        } catch (Exception e) {
            log.error("Error loading Anthropic models: {}", e.getMessage(), e);
        }

        // Add OpenAI models
        try {
            if (serviceHolder.getOpenAiService() != null) {
                com.openai.models.models.ModelListPage openAiModels = getOpenAiModels(serviceHolder.getOpenAiService().getClient());
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
            }
        } catch (Exception e) {
            log.error("Error adding OpenAI models: {}", e.getMessage(), e);
        }

        // Add Ollama models
        try {
            if (serviceHolder.getOllamaService() != null) {
                List<io.github.ollama4j.models.response.Model> ollamaModels = serviceHolder.getOllamaService().listModels();
                if (ollamaModels != null) {
                    for (io.github.ollama4j.models.response.Model ollamaModel : ollamaModels) {
                        String modelId = "ollama:" + ollamaModel.getName();
                        allModels.add(modelId);
                        log.debug("Added Ollama model to selector: {}", ollamaModel.getName());
                    }
                    log.info("Added {} Ollama models to selector", ollamaModels.size());
                }
            }
        } catch (Exception e) {
            log.error("Error adding Ollama models: {}", e.getMessage(), e);
        }

        // Add DeepSeek models
        try {
            if (serviceHolder.getDeepseekService() != null) {
                List<String> deepseekModels = getDeepSeekModelIds(serviceHolder.getDeepseekService());
                if (deepseekModels != null) {
                    for (String modelId : deepseekModels) {
                        allModels.add("deepseek:" + modelId);
                        log.debug("Added DeepSeek model to selector: {}", modelId);
                    }
                    log.info("Added {} DeepSeek models to selector", deepseekModels.size());
                }
            }
        } catch (Exception e) {
            log.error("Error adding DeepSeek models: {}", e.getMessage(), e);
        }

        // Add Google models
        try {
            if (serviceHolder.getGoogleService() != null) {
                List<String> googleModels = getGoogleModelIds(serviceHolder.getGoogleService());
                if (googleModels != null) {
                    for (String modelId : googleModels) {
                        allModels.add("google:" + modelId);
                        log.debug("Added Google model to selector: {}", modelId);
                    }
                    log.info("Added {} Google models to selector", googleModels.size());
                }
            }
        } catch (Exception e) {
            log.error("Error adding Google models: {}", e.getMessage(), e);
        }

        // Add Grok models
        try {
            if (serviceHolder.getGrokService() != null) {
                List<String> grokModels = getGrokModelIds(serviceHolder.getGrokService());
                if (grokModels != null) {
                    for (String modelId : grokModels) {
                        allModels.add("grok:" + modelId);
                        log.debug("Added Grok model to selector: {}", modelId);
                    }
                    log.info("Added {} Grok models to selector", grokModels.size());
                }
            }
        } catch (Exception e) {
            log.error("Error adding Grok models: {}", e.getMessage(), e);
        }

        // Add Meta Muse models
        try {
            if (serviceHolder.getMetaMuseService() != null) {
                List<String> metaMuseModels = getMetaMuseModelIds(serviceHolder.getMetaMuseService());
                if (metaMuseModels != null) {
                    for (String modelId : metaMuseModels) {
                        allModels.add("meta:" + modelId);
                        log.debug("Added Meta Muse model to selector: {}", modelId);
                    }
                    log.info("Added {} Meta Muse models to selector", metaMuseModels.size());
                }
            }
        } catch (Exception e) {
            log.error("Error adding Meta Muse models: {}", e.getMessage(), e);
        }

        // Add Bedrock models
        try {
            if (serviceHolder.getBedrockService() != null) {
                List<String> bedrockModels = getBedrockModelIds(serviceHolder.getBedrockService());
                if (bedrockModels != null) {
                    for (String modelId : bedrockModels) {
                        allModels.add("bedrock:" + modelId);
                        log.debug("Added Bedrock model to selector: {}", modelId);
                    }
                    log.info("Added {} Bedrock models to selector", bedrockModels.size());
                }
            }
        } catch (Exception e) {
            log.error("Error adding Bedrock models: {}", e.getMessage(), e);
        }

        return allModels;
    }

    private static List<String> getGoogleModelIds(GoogleAiService googleService) {
        return googleService.listModels();
    }

    private static List<String> getGrokModelIds(GrokAiService grokService) {
        if (grokService == null) {
            return new ArrayList<>();
        }
        return grokService.listModels();
    }

    private static List<String> getMetaMuseModelIds(MetaMuseAiService metaMuseService) {
        if (metaMuseService == null) {
            return new ArrayList<>();
        }
        return metaMuseService.listModels();
    }

    private static List<String> getBedrockModelIds(BedrockAiService bedrockService) {
        if (bedrockService == null) {
            return new ArrayList<>();
        }
        return bedrockService.listModels();
    }

    /**
     * Get a combined list of model IDs from all active services.
     *
     * @param serviceHolder Container holding all AI services
     * @return List of model IDs
     */
    public static List<String> getModelIds(AiServiceHolder serviceHolder) {
        List<String> modelIds = new ArrayList<>();
        if (serviceHolder == null) {
            return modelIds;
        }

        try {
            // Get Anthropic models
            if (serviceHolder.getClaudeService() != null) {
                List<String> anthropicModels = getAnthropicModelIds(serviceHolder.getClaudeService().getClient());
                if (anthropicModels != null) {
                    modelIds.addAll(anthropicModels);
                }
            }

            // Get OpenAI models
            if (serviceHolder.getOpenAiService() != null) {
                List<String> openAiModels = getOpenAiModelIds(serviceHolder.getOpenAiService().getClient());
                if (openAiModels != null) {
                    modelIds.addAll(openAiModels);
                }
            }

            // Get Ollama models
            if (serviceHolder.getOllamaService() != null) {
                List<String> ollamaModels = getOllamaModelIds(serviceHolder.getOllamaService());
                if (ollamaModels != null) {
                    modelIds.addAll(ollamaModels);
                }
            }

            // Get DeepSeek models
            if (serviceHolder.getDeepseekService() != null) {
                List<String> deepseekModels = getDeepSeekModelIds(serviceHolder.getDeepseekService());
                if (deepseekModels != null) {
                    modelIds.addAll(deepseekModels);
                }
            }

            // Get Google models
            if (serviceHolder.getGoogleService() != null) {
                List<String> googleModels = getGoogleModelIds(serviceHolder.getGoogleService());
                if (googleModels != null) {
                    modelIds.addAll(googleModels);
                }
            }

            // Get Grok models
            if (serviceHolder.getGrokService() != null) {
                List<String> grokModels = getGrokModelIds(serviceHolder.getGrokService());
                if (grokModels != null) {
                    modelIds.addAll(grokModels);
                }
            }

            // Get Meta Muse models
            if (serviceHolder.getMetaMuseService() != null) {
                List<String> metaMuseModels = getMetaMuseModelIds(serviceHolder.getMetaMuseService());
                if (metaMuseModels != null) {
                    modelIds.addAll(metaMuseModels);
                }
            }

            // Get Bedrock models
            if (serviceHolder.getBedrockService() != null) {
                List<String> bedrockModels = getBedrockModelIds(serviceHolder.getBedrockService());
                if (bedrockModels != null) {
                    modelIds.addAll(bedrockModels);
                }
            }

            log.info("Combined {} models from Anthropic, OpenAI, Ollama, DeepSeek, Google, Grok, Meta Muse, and Bedrock", modelIds.size());
            return modelIds;
        } catch (Exception e) {
            log.error("Error combining models: {}", e.getMessage(), e);
            return modelIds;
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
     *
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
     *
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
     *
     * @param client OpenAI client
     * @return OpenAI ModelListPage
     */
    public static com.openai.models.models.ModelListPage getOpenAiModels(OpenAIClient client) {
        try {
            log.info("Fetching available models from OpenAI API");
            client = OpenAIOkHttpClient.builder()
                    .apiKey(AiConfig.getProperty("openai.api.key", "YOUR_API_KEY"))
                    .build();

            com.openai.models.models.ModelListPage models = client.models().list();

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
     *
     * @param client OpenAI client
     * @return List of model IDs
     */
    public static List<String> getOpenAiModelIds(OpenAIClient client) {
        com.openai.models.models.ModelListPage models = getOpenAiModels(client);
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
                    .map(Model::id)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private static List<String> getDeepSeekModelIds(DeepseekAiService deepseekService) {
        try {
            if (deepseekService.isAnthropicFormat()) {
                com.anthropic.models.models.ModelListPage models = deepseekService.getAnthropicClient().models().list(
                        com.anthropic.models.models.ModelListParams.builder().build());
                if (models != null && models.data() != null) {
                    List<String> modelIds = new ArrayList<>();
                    for (com.anthropic.models.models.ModelInfo model : models.data()) {
                        modelIds.add(model.id());
                        log.debug("Available DeepSeek model (Anthropic format): {}", model.id());
                    }
                    log.info("Successfully retrieved {} models from DeepSeek API (Anthropic format)", modelIds.size());
                    return modelIds;
                }
            } else {
                com.openai.models.models.ModelListPage models = deepseekService.getClient().models().list();
                if (models != null && models.data() != null) {
                    List<String> modelIds = new ArrayList<>();
                    for (com.openai.models.models.Model model : models.data()) {
                        modelIds.add(model.id());
                        log.debug("Available DeepSeek model (OpenAI format): {}", model.id());
                    }
                    log.info("Successfully retrieved {} models from DeepSeek API (OpenAI format)", modelIds.size());
                    return modelIds;
                }
            }
        } catch (Exception e) {
            log.error("Error fetching models from DeepSeek API: {}", e.getMessage(), e);
        }
        return new ArrayList<>();
    }


}
