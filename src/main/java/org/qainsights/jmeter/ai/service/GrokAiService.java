package org.qainsights.jmeter.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.models.Model;
import org.qainsights.jmeter.ai.service.reasoning.DeepSeekReasoning;
import org.qainsights.jmeter.ai.service.reasoning.GrokReasoning;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI service implementation for X Grok (xAI).
 * <p>
 * Uses the OpenAI-compatible Chat Completions API at {@code https://api.x.ai/v1}
 * as documented at <a href="https://docs.x.ai/developers/quickstart">xAI Quickstart</a>.
 */
public class GrokAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(GrokAiService.class);

    private final OpenAIClient openAiClient;
    private final String baseUrl;
    private final int maxHistorySize;
    private final String systemPrompt;
    private String model;
    private final float temperature;
    private final long maxTokens;
    private ReasoningSettings reasoningSettings;
    private String lastReasoning;
    private org.qainsights.jmeter.ai.service.usage.UsageStats usageStats;
    private final java.util.concurrent.atomic.AtomicBoolean extraFieldsLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public GrokAiService() {
        String apiKey = AiConfig.getProperty("grok.api.key", "");
        this.baseUrl = AiConfig.getProperty("grok.base.url", "https://api.x.ai/v1");
        this.model = AiConfig.getProperty("grok.default.model", "grok-4.5");
        this.temperature = ModelUtils.parseTemperature(
                AiConfig.getProperty("grok.temperature", "0.7"));
        this.maxHistorySize = Integer.parseInt(
                AiConfig.getProperty("grok.max.history.size", "10"));
        this.maxTokens = Long.parseLong(
                AiConfig.getProperty("grok.max.tokens", "4096"));

        String configuredPrompt = AiConfig.getProperty("grok.system.prompt", "");
        this.systemPrompt = (configuredPrompt != null && !configuredPrompt.isEmpty())
                ? configuredPrompt : Constants.DEFAULT_JMETER_SYSTEM_PROMPT;

        if (apiKey != null && !apiKey.isEmpty() && !"YOUR_GROK_API_KEY".equals(apiKey)) {
            this.openAiClient = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
        } else {
            this.openAiClient = null;
        }

        log.info("Initialized Grok service with baseUrl: {}, model: {}, temperature: {}",
                this.baseUrl, this.model, this.temperature);
    }

    /** Package-private constructor for testing. */
    GrokAiService(OpenAIClient openAiClient, String baseUrl, String model,
                  float temperature, int maxHistorySize, long maxTokens,
                  String systemPrompt) {
        this.openAiClient = openAiClient;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxHistorySize = maxHistorySize;
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt;
    }

    public OpenAIClient getClient() {
        return openAiClient;
    }

    public void setModel(String modelId) {
        this.model = modelId;
        log.info("Grok model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return model;
    }

    @Override
    public void setReasoningSettings(ReasoningSettings settings) {
        this.reasoningSettings = settings;
    }

    @Override
    public String consumeLastReasoning() {
        String reasoning = lastReasoning;
        lastReasoning = null;
        return reasoning;
    }

    /** Receives the session usage accumulator (context-stats label). */
    @Override
    public void setUsageStats(org.qainsights.jmeter.ai.service.usage.UsageStats stats) {
        this.usageStats = stats;
    }

    @Override
    public String getName() {
        return "Grok";
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, this.model);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        if (openAiClient == null) {
            return "Error: Grok client not initialized. Set grok.api.key in jmeter.properties.";
        }
        try {
            String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;

            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .maxCompletionTokens(maxTokens)
                    .model(modelToUse)
                    .temperature((double) temperature);

            GrokReasoning.effortFor(reasoningSettings, modelToUse).ifPresent(effort -> {
                paramsBuilder.reasoningEffort(effort);
                log.info("Reasoning effort set to {} for model {}", effort, modelToUse);
            });

            paramsBuilder.addSystemMessage(systemPrompt);

            List<String> cleanHistory = filterErrorMessages(buildLimitedHistory(conversation));
            if (cleanHistory.isEmpty()) {
                paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
            } else {
                appendConversation(paramsBuilder, cleanHistory);
            }

            ChatCompletionCreateParams params = paramsBuilder.build();
            ChatCompletion chatCompletion = openAiClient.chat().completions().create(params);

            if (usageStats != null) {
                chatCompletion.usage().ifPresent(usage ->
                        usageStats.record("grok:" + modelToUse, usage.promptTokens(), usage.completionTokens()));
            }

            ChatCompletion.Choice choice = chatCompletion.choices().get(0);
            lastReasoning = DeepSeekReasoning.reasoningContent(
                    choice.message()._additionalProperties());
            return choice.message().content().orElse("No content available");

        } catch (Exception e) {
            log.error("Error generating response from Grok", e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model,
                                           Consumer<String> tokenConsumer,
                                           Runnable onComplete,
                                           Consumer<Exception> onError) {
        return generateStreamResponse(conversation, model, tokenConsumer,
                reasoning -> {}, onComplete, onError);
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model,
                                           Consumer<String> tokenConsumer,
                                           Consumer<String> reasoningConsumer,
                                           Runnable onComplete,
                                           Consumer<Exception> onError) {
        if (openAiClient == null) {
            return () -> {};
        }
        String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .maxCompletionTokens(maxTokens)
                .model(modelToUse)
                .temperature((double) temperature)
                .streamOptions(ChatCompletionStreamOptions.builder()
                        .includeUsage(true)
                        .build());

        GrokReasoning.effortFor(reasoningSettings, modelToUse).ifPresent(effort -> {
            paramsBuilder.reasoningEffort(effort);
            log.info("Reasoning effort set to {} for model {}", effort, modelToUse);
        });

        paramsBuilder.addSystemMessage(systemPrompt);

        List<String> cleanHistory = filterErrorMessages(buildLimitedHistory(conversation));
        if (cleanHistory.isEmpty()) {
            paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
        } else {
            appendConversation(paramsBuilder, cleanHistory);
        }

        ChatCompletionCreateParams params = paramsBuilder.build();

        Thread streamThread = new Thread(() -> {
            try {
                java.util.concurrent.atomic.AtomicReference<com.openai.models.completions.CompletionUsage>
                        streamUsage = new java.util.concurrent.atomic.AtomicReference<>();
                try (com.openai.core.http.StreamResponse<ChatCompletionChunk> stream =
                             openAiClient.chat().completions().createStreaming(params)) {
                    stream.stream()
                            .forEach(chunk -> {
                                chunk.usage().ifPresent(streamUsage::set);
                                chunk.choices().forEach(choice -> {
                                    java.util.Map<String, com.openai.core.JsonValue> extraFields =
                                            choice.delta()._additionalProperties();
                                    if (!extraFields.isEmpty() && extraFieldsLogged.compareAndSet(false, true)) {
                                        log.info("Grok stream extra fields: {}", extraFields.keySet());
                                    }
                                    String reasoning = DeepSeekReasoning.reasoningContent(extraFields);
                                    if (reasoning != null && !reasoning.isEmpty()) {
                                        javax.swing.SwingUtilities.invokeLater(
                                                () -> reasoningConsumer.accept(reasoning));
                                    }
                                    choice.delta().content().ifPresent(text ->
                                            javax.swing.SwingUtilities.invokeLater(
                                                    () -> tokenConsumer.accept(text)));
                                });
                            });
                }
                if (usageStats != null && streamUsage.get() != null) {
                    usageStats.record("grok:" + modelToUse,
                            streamUsage.get().promptTokens(), streamUsage.get().completionTokens());
                }
                javax.swing.SwingUtilities.invokeLater(onComplete);
            } catch (Exception e) {
                log.error("Error in Grok streaming response", e);
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();

        return () -> {
            log.info("Cancelling Grok stream");
            if (streamThread.isAlive()) {
                streamThread.interrupt();
            }
        };
    }

    /**
     * Lists available Grok models from the xAI API, filtered to chat-capable models.
     */
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        if (openAiClient == null) {
            return models;
        }
        try {
            com.openai.models.models.ModelListPage page = openAiClient.models().list();
            if (page != null && page.data() != null) {
                models = page.data().stream()
                        .map(Model::id)
                        .filter(GrokAiService::isChatModel)
                        .collect(Collectors.toList());
            }
            log.info("Retrieved {} Grok models", models.size());
        } catch (Exception e) {
            log.error("Error listing Grok models: {}", e.getMessage(), e);
        }
        return models;
    }

    private static boolean isChatModel(String modelId) {
        String lower = modelId.toLowerCase();
        return lower.startsWith("grok")
                && !lower.contains("embedding")
                && !lower.contains("image");
    }

    private void appendConversation(ChatCompletionCreateParams.Builder builder,
                                    List<String> history) {
        for (int i = 0; i < history.size(); i++) {
            String msg = history.get(i);
            if (msg == null || msg.isEmpty()) {
                continue;
            }
            if (i % 2 == 0) {
                builder.addUserMessage(msg);
            } else {
                builder.addAssistantMessage(msg);
            }
        }
    }

    private List<String> buildLimitedHistory(List<String> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return new ArrayList<>();
        }
        if (conversation.size() > maxHistorySize) {
            return conversation.subList(conversation.size() - maxHistorySize,
                    conversation.size());
        }
        return new ArrayList<>(conversation);
    }

    private List<String> filterErrorMessages(List<String> messages) {
        List<String> clean = new ArrayList<>();
        for (String msg : messages) {
            if (msg != null && !msg.startsWith("Error:")) {
                clean.add(msg);
            }
        }
        return clean;
    }
}
